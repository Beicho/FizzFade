#!/usr/bin/env python3
"""Read raw DEX declarations (not JADX aliases) and check this adapter's contract.

Both official builds this module supports pass: 21.6.0 and 21.8.0. Their only contract
difference is the wrapper's player accessor pair, checked per build at the end.
"""
import argparse
import hashlib
import json
import re
import struct
import zipfile
from pathlib import Path

# sha256 -> official build name; the module supports both, so either one passes.
EXPECTED_SHA256 = {
    "09da4d38716e8d0fb2b5cbaa8d6454a88f9b657f6a7a38facd283bacb204ae22": "21.6.0",
    "edb0cc9cac46c234d8b3879e0f52a5cb987351e6aa69e40d2a4d5e2a5f144676": "21.8.0",
}
P = "com.luna.common.player."
B = "com.luna.biz.playing.player.bach."


def dex_contract(data, wanted):
    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]

    def leb(offset):
        value, shift = 0, 0
        while True:
            byte = data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value, offset
            shift += 7

    strings = []
    for i in range(u32(56)):
        _, offset = leb(u32(u32(60) + 4 * i))
        strings.append(data[offset:data.index(0, offset)].decode("utf-8", errors="replace"))
    types = [strings[u32(u32(68) + 4 * i)] for i in range(u32(64))]
    result = {}
    for i in range(u32(96)):
        offset = u32(100) + 32 * i
        descriptor = types[u32(offset)]
        name = descriptor[1:-1].replace("/", ".")
        if name not in wanted:
            continue
        class_data = u32(offset + 24)
        record = {"methods": [], "fields": []}
        result[name] = record
        if not class_data:
            continue
        counts = []
        for _ in range(4):
            count, class_data = leb(class_data)
            counts.append(count)
        for count in counts[:2]:
            index = 0
            for _ in range(count):
                delta, class_data = leb(class_data)
                _, class_data = leb(class_data)
                index += delta
                _, field_type, field_name = struct.unpack_from("<HHI", data, u32(84) + 8 * index)
                record["fields"].append(strings[field_name] + ":" + types[field_type])
        for count in counts[2:]:
            index = 0
            for _ in range(count):
                delta, class_data = leb(class_data)
                _, class_data = leb(class_data)
                _, class_data = leb(class_data)
                index += delta
                _, proto, method_name = struct.unpack_from("<HHI", data, u32(92) + 8 * index)
                proto_offset = u32(76) + 12 * proto
                parameter_offset = u32(proto_offset + 8)
                parameters = ""
                if parameter_offset:
                    parameters = "".join(types[struct.unpack_from("<H", data, parameter_offset + 4 + 2 * j)[0]]
                                         for j in range(u32(parameter_offset)))
                record["methods"].append(strings[method_name] + "(" + parameters + ")" + types[u32(proto_offset + 4)])
    return result


def desc(name):
    return "L" + name.replace(".", "/") + ";"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    playable = desc(P + "queue.api.IPlayable")
    reason = desc(P + "mediaplayer.PlayReason")
    media = desc(P + "mediaplayer.api.IMediaPlayer")
    fields = {
        P + "kit.impl.TTMediaPlayer": ["b:Lcom/ss/ttvideoengine/TTVideoEngine;", "e:" + desc(P + "datasource.StreamMediaDataSource")],
        # 0.6.0 pause repair: the start flag that gates innerPause's engine pause.
        P + "mediaplayer.impl.MediaPlayer": ["C:Z", "A:Z"],
        B + "InnerPlayer": ["j:" + desc(P + "CompositePlayerInterceptor")],
        B + "MediaPlayerFactory": ["a:" + desc(P + "PlayerType")],
        P + "mediaplayer.PlayReason$PlayComplete": ["a:" + desc(P + "mediaplayer.PlayReason$PlayComplete")],
        P + "prerender.PreRenderTrigger$RenderStart": ["a:" + desc(P + "prerender.PreRenderTrigger$RenderStart")],
        P + "mediaplayer.PlayerSubType": ["MAIN:" + desc(P + "mediaplayer.PlayerSubType"), "PRERENDER:" + desc(P + "mediaplayer.PlayerSubType")],
    }
    fields.update({
        "com.ss.ttvideoengine.TTVideoEngine": ["mVideoEngine:Lcom/ss/ttvideoengine/TTVideoEngineInterface;"],
        "com.ss.ttvideoengine.TTVideoEngineInternal": ["mMediaPlayer:Lcom/ss/ttm/player/MediaPlayer;"],
        "com.ss.ttm.player.AJVoice": ["mAudioTrack:Landroid/media/AudioTrack;", "mSampleRate:I", "mChannels:I", "mAudioFormat:I", "mWrittenPcmBytes:J", "mPCMFrameBuffer:Ljava/nio/ByteBuffer;"],
        B + "prerender.MediaPlayerWrapper": ["z:" + desc(P + "mediaplayer.api.IMediaPlayerProvider")],
        P + "mediaplayer.api.PreRenderDiscardReason$PreRenderUnprepared": ["a:" + desc(P + "mediaplayer.api.PreRenderDiscardReason$PreRenderUnprepared")],
    })
    methods = {
        B + "MediaPlayerFactory": ["a(" + desc(P + "bach.IInnerPlayerController") + desc(P + "mediaplayer.MediaPlayerConfig") + ")" + media, "d()Z"],
        B + "prerender.PreRenderSettingsConfig": ["F()Z"],
        B + "prerender.MediaPlayerWrapper": [
            "c3(" + desc(P + "prerender.PreRenderTrigger") + playable + ")Z",
            "onPlaybackTimeChanged(" + playable + "J)V", "onPlaybackTimeChangedFast(" + playable + "J)V",
            "onRenderStart(" + playable + ")V", "triggerPreRender(" + desc(P + "prerender.PreRenderTrigger") + playable + ")V",
            "play(" + reason + ")Z", "isPlaying()Z", "getPlaybackSpeed()F", "seekToTime(J" + desc(P + "mediaplayer.seek.SeekContext") + ")V",
            "pause(" + desc(P + "mediaplayer.PauseReason") + ")Z", "stop(" + desc(P + "mediaplayer.StopReason") + ")V",
            "onAudioFocusChange(" + desc(P + "mediaplayer.ext.audiofocus.AudioFocusChangeContext") + ")V"],
        B + "InnerPlayer": ["getExecutor()" + desc(P + "executor.IPlayerThreadExecutor"),
            "isRealPlaying()Z", "getNextQueueItem()" + desc(P + "queue.api.IQueueItem"),
            "getCurrentQueueLoopMode()" + desc(P + "queue.mode.QueueLoopMode"), "getSelectedSleepTimeS()Ljava/lang/Long;",
            "getLeftSleepTimeData()" + desc(P + "SleepTimeData"), "changeToNextQueueItem(" + reason + ")Z", "play(" + reason + ")Z"],
        P + "executor.IPlayerThreadExecutor": ["X(Ljava/lang/Runnable;)V"],
        P + "mediaplayer.impl.MediaPlayer": ["k3()" + desc(P + "kit.impl.TTMediaPlayer"), "getPlayable()" + playable,
            "isPrepared()Z", "getRealMediaResType()" + desc(P + "mediaplayer.MediaResType"),
            "getAVInfo(" + desc(P + "mediaplayer.PlayerSubType") + ")" + desc(P + "kit.api.AVInfo"),
            "getPlaybackTime()I", "getDuration()I", "isPlaying()Z",
            "play(" + reason + ")Z", "pause(" + desc(P + "mediaplayer.PauseReason") + ")Z"],
        "com.ss.ttvideoengine.TTVideoEngine": ["setVolume(FF)V", "getVolume()F", "getCurrentPlaybackTime()I", "getDuration()I", "getIntOption(I)I",
            "isMute()Z", "setIsMute(Z)V", "pause()V", "stop()V", "release()V", "releaseAsync()V",
            "setAudioProcessor(Lcom/ss/ttm/player/AudioProcessor;)V"],
        "com.luna.common.arch.ext.PlayableExtKt": ["getMediaType(" + playable + ")Lcom/luna/common/arch/db/entity/NetMediaType;"],
        "com.luna.common.arch.db.entity.NetMediaType": ["getServerValue()Ljava/lang/String;"],
        P + "PlayerType": ["getValue()Ljava/lang/String;"],
        P + "queue.api.IQueueItem": ["getPlayId()Ljava/lang/String;", "isRewardAdPlayable()Z"],
        P + "SleepTimeData": ["getWillSleepAfterPlay()Ljava/lang/Boolean;"],
        P + "kit.api.AVInfo": ["getMediaResType()" + desc(P + "mediaplayer.MediaResType")],
        P + "datasource.StreamMediaDataSource": ["close()V"],
        "com.luna.audio.effect.AudioEffectProcessor": ["audioOpen(IIII)V", "audioProcess([Ljava/nio/ByteBuffer;IJ)V"],
        "com.luna.biz.playing.playpage.pager.delegate.viewmodel.livedatacontroller.AutoChangeToNextLiveDataController$mPlayerInterceptor$2$1": ["onInterceptAutoChangeToNextPlayable(" + playable + ")" + desc(P + "mediaplayer.api.AutoChangeToNextInterceptResult")],
        P + "CompositePlayerInterceptor": ["onInterceptAutoChangeToNextPlayable(" + playable + ")" + desc(P + "mediaplayer.api.AutoChangeToNextInterceptResult")],
        P + "mediaplayer.api.AutoChangeToNextInterceptResult": ["isIntercepted()Z", "getWillPlay()Z", "getErrorCode()Ljava/lang/Integer;", "<init>(ZZLjava/lang/Integer;)V"],
    }
    methods["com.ss.ttvideoengine.TTVideoEngine"] += ["play()V", "setPlaybackParams(Lcom/ss/ttm/player/PlaybackParams;)V", "seekTo(ILcom/ss/ttvideoengine/SeekCompletionListener;)V",
        "getPlaybackState()I"]
    methods.update({
        "com.ss.ttvideoengine.MediaPlayerWrapper": ["getPlayerClient()Lcom/ss/ttm/player/MediaPlayerClient;", "getIntOption(II)I"],
        "com.ss.ttm.player.MediaPlayer": ["getIntOption(II)I"],
        "com.ss.ttm.player.AJVoice": ["write([BIII)I", "write([BIIIJ)I", "write(III)I", "setPlaybackSpeed(F)I"],
        "com.ss.ttm.player.PlaybackParams": ["<init>()V", "setSpeed(F)Lcom/ss/ttm/player/PlaybackParams;", "setPitch(F)Lcom/ss/ttm/player/PlaybackParams;", "getSpeed()F", "getPitch()F"],
        "com.ss.ttvideoengine.SeekCompletionListener": ["onCompletion(Z)V"],
        P + "mediaplayer.api.IMediaPlayerProvider": ["A0(" + desc(P + "mediaplayer.api.PreRenderDiscardReason") + ")V"],
    })
    found = {}
    wanted = set(fields) | set(methods)
    sha = hashlib.sha256(args.apk.read_bytes()).hexdigest()
    with zipfile.ZipFile(args.apk) as apk:
        for name in apk.namelist():
            if re.fullmatch(r"classes\d*\.dex", name):
                found.update(dex_contract(apk.read(name), wanted))
    failures, checked = [], []
    for kind, requirement in [("fields", fields), ("methods", methods)]:
        for name, signatures in requirement.items():
            for signature in signatures:
                label = name + "#" + signature
                if signature in found.get(name, {}).get(kind, []):
                    checked.append(label)
                else:
                    failures.append(label)
    # 21.8.0 renamed the wrapper's player accessors once: current/upcoming are G2/H2 there and H2/I2
    # before. Only the new build has a zero-argument G2 (21.6.0's argument-taking G2 is another
    # method), so its presence selects the pair.
    wrapper = found.get(B + "prerender.MediaPlayerWrapper", {}).get("methods", [])
    accessors = ["G2()" + media, "H2()" + media] if "G2()" + media in wrapper else ["H2()" + media, "I2()" + media]
    for signature in accessors:
        label = B + "prerender.MediaPlayerWrapper#" + signature
        (checked if signature in wrapper else failures).append(label)
    if sha not in EXPECTED_SHA256:
        failures.append("APK SHA-256 is not one of the official analyzed builds")
    report = {"apk_sha256": sha, "apk_build": EXPECTED_SHA256.get(sha, "unknown"),
              "passed": not failures, "checked_count": len(checked),
              "missing": failures, "checked": checked}
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: report[key] for key in ("apk_build", "passed", "checked_count", "missing")}, ensure_ascii=False, indent=2))
    raise SystemExit(1 if failures else 0)


if __name__ == "__main__":
    main()
