package com.ss.ttm.player;
import java.nio.ByteBuffer;
public class AJVoice {
 public long mNativeObject;public android.media.AudioTrack mAudioTrack=new android.media.AudioTrack();
 public int mSampleRate=48000,mChannels=1,mAudioFormat=2;
 public long mWrittenPcmBytes; public ByteBuffer mPCMFrameBuffer=ByteBuffer.allocateDirect(3840);
 public int result=Integer.MAX_VALUE;public android.media.AudioTrack replacement;
 private int finish(int size){if(replacement!=null)mAudioTrack=replacement;return result==Integer.MAX_VALUE?size:result;}
 public int write(byte[] a,int offset,int size,int serial){return finish(size);}
 public int write(byte[] a,int offset,int size,int serial,long pts){return finish(size);}
 public int write(int offset,int size,int serial){return finish(size);}
}
