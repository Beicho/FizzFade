package com.luna.biz.playing.playpage.track;
import com.luna.biz.playing.common.playpage.IPlayerControllerProvider;
import com.luna.common.player.queue.api.IPlayerController;
public abstract class BaseCompleteTrackCardViewDelegate extends BaseTrackCardViewDelegate {
    public static IPlayerController shared;
    // Anonymous class in the host: the only field, typed by its synthetic class.
    private final IPlayerControllerProvider z = new IPlayerControllerProvider() {
        @Override public IPlayerController getPlayerController() { return shared; }
        @Override public Object getShadowPlayerController() { return null; }
    };
}
