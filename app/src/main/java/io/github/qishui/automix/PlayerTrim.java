package io.github.qishui.automix;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Subtraction on the play page. Each target is found by structure and class name (both survive
 * the host's obfuscation) and kept GONE: the host re-shows some of them on every song, so the
 * owner calls {@link #enforce} from a pre-draw listener. Touching only visibility, never logic.
 */
final class PlayerTrim {
    /** Card-level targets: the tag row after the title, the follow button after the artists, the cover page dots. */
    static void card(ViewGroup root, HostDiagnostics.Ui ui, List<View> out) {
        out.clear();
        if (ui.trimTags) {
            View name = byClass(root, ".NameContainerView", 8);
            if (name instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) name;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View c = g.getChildAt(i);
                    if (!c.getClass().getName().endsWith("MarqueeView")) out.add(c);
                }
            }
        }
        if (ui.trimFollow) {
            View artists = byClass(root, ".ArtistsTruncatingLayout", 8);
            if (artists != null && artists.getParent() instanceof ViewGroup) {
                ViewGroup row = (ViewGroup) artists.getParent();
                for (int i = row.indexOfChild(artists) + 1; i < row.getChildCount(); i++) out.add(row.getChildAt(i));
            }
        }
        if (ui.trimDots) {
            View dots = byClass(root, ".DotPagerIndicator", 6);
            if (dots != null) out.add(dots);
        }
    }

    /** Overlays that keep their place hidden; the rest give their space back. */
    private static boolean overlay(View v) { return v.getClass().getName().endsWith(".DotPagerIndicator"); }

    /** Hides every target that came back; returns how many were hidden this time. */
    static int enforce(List<View> targets) {
        int hidden = 0;
        for (int i = 0; i < targets.size(); i++) {
            View v = targets.get(i);
            int goal = overlay(v) ? View.INVISIBLE : View.GONE;
            if (v.getVisibility() == View.VISIBLE) { v.setVisibility(goal); hidden++; }
        }
        return hidden;
    }

    /**
     * The delegate's root view: among its View fields, the one that contains all the others that
     * are set. Null when nothing is set yet or no field contains the rest.
     */
    static View root(Object delegate, Field[] fields) throws IllegalAccessException {
        List<View> set = new ArrayList<>();
        for (Field f : fields) { Object v = f.get(delegate); if (v instanceof View) set.add((View) v); }
        for (View candidate : set) {
            boolean all = true;
            for (View other : set) if (other != candidate && !contains(candidate, other)) { all = false; break; }
            if (all && (set.size() > 1 || candidate instanceof ViewGroup)) return candidate;
        }
        return null;
    }

    static boolean contains(View ancestor, View view) {
        for (ViewParent p = view.getParent(); p != null; p = p.getParent()) if (p == ancestor) return true;
        return false;
    }

    static View byClass(View view, String suffix, int depth) {
        if (view.getClass().getName().endsWith(suffix)) return view;
        if (depth == 0 || !(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View hit = byClass(group.getChildAt(i), suffix, depth - 1);
            if (hit != null) return hit;
        }
        return null;
    }

    private PlayerTrim() { }
}
