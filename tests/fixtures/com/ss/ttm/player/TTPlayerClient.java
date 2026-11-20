package com.ss.ttm.player;
public final class TTPlayerClient {
 private static int sequence=1000; public int session=sequence++;
 public int getIntOption(int option,int fallback){return option==961?session:fallback;}
}
