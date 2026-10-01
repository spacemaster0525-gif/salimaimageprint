package com.salimaprint.app.render;

import java.io.ByteArrayOutputStream;

/**
 * تجميع مهمة طباعة Canon MF3010 (إطار CD CA) من مقدمة/خاتمة ثابتة + بيانات الـ bands.
 * تم التحقق من إعادة بناء 5 مهام ملتقطة بايتاً ببايت.
 */
public final class Mf3010Job {
    private Mf3010Job() {}

    /** BIH لـ JBIG: عرض 4720، ارتفاع 4516، شريحة 128، خيارات LRLTWO|TPBON */
    private static final byte[] BIH = {
        0, 0, 1, 0, 0, 0, 0x12, 0x70, 0, 0, 0x11, (byte) 0xa4, 0, 0, 0, (byte) 0x80, 0, 0, 0, 0x48 };

    private static final int TOTAL_LEN_OFFSET_IN_SUFFIX = 159;   // حقل 4 بايت big-endian

    private static void header(ByteArrayOutputStream o, int flags, int cmd, int plen) {
        o.write(0xcd); o.write(0xca);
        w16(o, flags); w16(o, cmd); w16(o, 1); w16(o, plen);
        o.write(0xff); o.write(0xff);
        for (int i = 0; i < 8; i++) o.write(0);
    }
    private static void w16(ByteArrayOutputStream o, int v) { o.write(v >> 8); o.write(v); }

    private static void lengthSection(ByteArrayOutputStream p, int n) {
        if (n < 256) { p.write(0xa0); p.write(n); p.write(0x9c); p.write(n); }
        else { p.write(0xa4); w16(p, n); p.write(0x9d); w16(p, n); }
    }

    /** @param bands بيانات JBIG لكل band (0..17)، @param rows عدد أسطر كل band */
    public static byte[] build(byte[] prefix, byte[] suffix, byte[][] bands, int[] rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(prefix, 0, prefix.length);
        for (int idx = 0; idx < bands.length; idx++) {
            ByteArrayOutputStream p = new ByteArrayOutputStream();
            byte[] data = bands[idx];
            if (idx == 0) {
                byte[] head = {0x01, 0x61, (byte) 0xe6, (byte) 0x80, 0x01, (byte) 0xe5, 0x00, 0x62,
                    (byte) 0xe3, (byte) 0x85, 0x12, 0x70};
                p.write(head, 0, head.length);
                w16(p, rows[idx]);
                byte[] mid = {(byte) 0xe8, (byte) 0xa1, 0, 0, (byte) 0xe1, 0x05, (byte) 0xd7};
                p.write(mid, 0, mid.length);
                lengthSection(p, BIH.length + data.length);
                p.write(BIH, 0, BIH.length);
                p.write(data, 0, data.length);
            } else {
                byte[] head = {0x01, 0x62, (byte) 0xe3, (byte) 0x85, 0x12, 0x70};
                p.write(head, 0, head.length);
                w16(p, rows[idx]);
                byte[] mid = {(byte) 0xe8, (byte) 0xa5, 0, 0, (byte) idx, 0, (byte) 0xe1, 0x05, (byte) 0xd7};
                p.write(mid, 0, mid.length);
                lengthSection(p, data.length);
                p.write(data, 0, data.length);
            }
            header(out, 0x1002, 0x1a, p.size());
            byte[] pb = p.toByteArray();
            out.write(pb, 0, pb.length);
        }
        byte[] suf = suffix.clone();
        int total = out.size() + suf.length;
        suf[TOTAL_LEN_OFFSET_IN_SUFFIX] = (byte) (total >> 24);
        suf[TOTAL_LEN_OFFSET_IN_SUFFIX + 1] = (byte) (total >> 16);
        suf[TOTAL_LEN_OFFSET_IN_SUFFIX + 2] = (byte) (total >> 8);
        suf[TOTAL_LEN_OFFSET_IN_SUFFIX + 3] = (byte) total;
        out.write(suf, 0, suf.length);
        return out.toByteArray();
    }
}
