package autonewsroller.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class Hashing {
    private Hashing() {}
    public static String sha256(String value) {
        try {
            byte[] d=MessageDigest.getInstance("SHA-256").digest((value==null?"":value).getBytes(StandardCharsets.UTF_8));
            StringBuilder b=new StringBuilder(); for(byte x:d)b.append(String.format("%02x",x)); return b.toString();
        } catch(Exception e){ throw new IllegalStateException(e); }
    }
}
