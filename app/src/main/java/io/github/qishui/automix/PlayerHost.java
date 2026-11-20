package io.github.qishui.automix;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reflection contract with the host's portrait track card. The card delegate classes keep their
 * names in 21.6.0 and {@code onViewCreated(View, Bundle)} is a lifecycle method, so only the
 * player-controller provider field is matched by shape. Nothing here can start, pause or seek:
 * the controller is used solely to add a listener.
 */
final class PlayerHost {
    static final String TRACK = "com.luna.biz.playing.playpage.track.";

    final Class<?> baseCard, completeCard, portraitCard, providerType, playerController, playerListener;
    final Method viewCreated, providerPlayer, addListener, removeListener;
    final Field provider;

    PlayerHost(ClassLoader loader) throws Exception {
        baseCard = LyricsHost.load(loader, TRACK + "BaseTrackCardViewDelegate");
        completeCard = LyricsHost.load(loader, TRACK + "BaseCompleteTrackCardViewDelegate");
        portraitCard = LyricsHost.load(loader, TRACK + "PortraitTrackCardViewDelegate");
        providerType = LyricsHost.load(loader, "com.luna.biz.playing.common.playpage.IPlayerControllerProvider");
        playerController = LyricsHost.load(loader, "com.luna.common.player.queue.api.IPlayerController");
        playerListener = LyricsHost.load(loader, "com.luna.common.player.queue.api.IPlayerListener");
        if (!completeCard.isAssignableFrom(portraitCard) || !baseCard.isAssignableFrom(completeCard))
            throw new ClassNotFoundException("track card hierarchy changed");

        viewCreated = baseCard.getDeclaredMethod("onViewCreated", android.view.View.class, android.os.Bundle.class);
        viewCreated.setAccessible(true);
        provider = LyricsHost.one(completeCard, f -> providerType.isAssignableFrom(f.getType()), "BaseCompleteTrackCardViewDelegate.provider");
        providerPlayer = providerType.getMethod("getPlayerController");
        if (providerPlayer.getReturnType() != playerController) throw new NoSuchMethodException("IPlayerControllerProvider.getPlayerController type");
        addListener = playerController.getMethod("addPlayerListener", playerListener);
        removeListener = playerController.getMethod("removePlayerListener", playerListener);
    }

    String describe() { return "card=" + portraitCard.getSimpleName() + " hook=" + viewCreated.getName() + " provider=" + provider.getName(); }

    /** Only the phone portrait card; landscape, pad landscape and every other card stay native. */
    boolean portrait(Object delegate) { return delegate != null && delegate.getClass() == portraitCard; }

    Object player(Object delegate) throws Exception {
        Object p = provider.get(delegate);
        return p == null ? null : providerPlayer.invoke(p);
    }
}
