package com.ss.ttm.player;
public class PlaybackParams {
 public float speed=-1,pitch=-1;
 public float getSpeed(){return speed;}
 public float getPitch(){return pitch;}
 public PlaybackParams setSpeed(float speed){this.speed=speed;return this;}
 public PlaybackParams setPitch(float pitch){this.pitch=pitch;return this;}
}
