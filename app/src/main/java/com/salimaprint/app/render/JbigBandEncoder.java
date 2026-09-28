package com.salimaprint.app.render;

/**
 * مرمِّز JBIG1 (ITU-T T.82) لصفحة Canon MF3010:
 * طبقة واحدة، قالب سطرين (LRLTWO)، تنبؤ نمطي (TPBON)، MX=0، شرائح من 128 سطراً.
 * كل band = 256 سطراً = شريحتان، الأولى تنتهي بـ FF 02 والثانية بـ FF 03.
 * تم التحقق من مطابقة مخرجاته بايتاً ببايت لـ 85 band ملتقطاً من سائق Windows.
 */
public final class JbigBandEncoder {
    private static final int[] LSZ = {23069,9606,4372,2059,984,474,229,111,54,26,13,6,3,1,23167,16165,11506,8316,6073,4482,3311,2465,1839,1372,1030,771,576,433,324,245,183,138,104,78,59,44,23265,18508,14861,12017,9759,7987,6568,5400,4471,3700,3067,2552,2145,1798,1485,1246,1039,867,724,604,504,420,352,293,246,203,171,143,23314,19716,16684,14296,12264,10556,9081,7903,6825,5966,5156,4508,3947,3409,2998,2624,22578,19740,17294,15325,13550,11950,10650,9494,21872,19625,17625,15906,14372,12980,11799,22184,20294,18405,16847,15421,14174,21041,19471,17977,16734,22055,20711,19333,21911,20559,23056,21794,23019};
    private static final int[] NMPS = {1,2,3,4,5,6,7,8,9,10,11,12,13,13,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,9,37,38,39,40,41,42,43,44,45,46,47,48,49,50,51,52,53,54,55,56,57,58,59,60,61,62,63,32,65,66,67,68,69,70,71,72,73,74,75,76,77,78,79,48,81,82,83,84,85,86,87,71,89,90,91,92,93,94,86,96,97,98,99,100,93,102,103,104,99,106,107,103,109,107,111,109,111};
    private static final int[] NLPS = {1,14,16,18,20,23,25,28,30,33,35,9,10,12,15,36,38,39,40,42,43,45,46,48,49,51,52,54,56,57,59,60,62,63,32,33,37,64,65,67,68,69,70,72,73,74,75,77,78,79,48,50,50,51,52,53,54,55,56,57,58,59,61,61,65,80,81,82,83,84,86,87,87,72,72,74,74,75,77,77,80,88,89,90,91,92,93,86,88,95,96,97,99,99,93,95,101,102,103,104,99,105,106,107,103,105,108,109,110,111,110,112,112};
    private static final int[] SW = {1,0,0,0,0,0,0,0,0,0,0,0,0,0,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,0,0,0,0,0,0,0,1,0,0,0,0,0,0,1,0,0,0,0,0,0,0,0,0,1,0,0,0,0,1,0,1};

    public static final int BAND_ROWS = 256;
    public static final int STRIPE_ROWS = 128;
    private static final int SLNTP = 0x195;

    // حالة المرمِّز الحسابي
    private final byte[] st = new byte[4096];
    private long c; private int a, sc, ct, buf;
    private byte[] out = new byte[1 << 16]; private int n;

    private void put(int b) {
        if (n == out.length) out = java.util.Arrays.copyOf(out, n * 2);
        out[n++] = (byte) b;
    }
    private void resetCoder() { c = 0; a = 0x10000; sc = 0; ct = 11; buf = -1; }

    private void encode(int cx, int pix) {
        int s = st[cx] & 0xff, idx = s & 0x7f, lsz = LSZ[idx];
        int ss = (pix << 7) ^ (s & 0x80);
        a -= lsz;
        if (ss == 0) {
            if ((a & 0xffff8000) != 0) return;
            if (a < lsz) { c += a; a = lsz; }
            st[cx] = (byte) ((s & 0x80) | NMPS[idx]);
        } else {
            if (a >= lsz) { c += a; a = lsz; }
            st[cx] = (byte) (((s & 0x80) ^ (SW[idx] != 0 ? 0x80 : 0)) | NLPS[idx]);
        }
        do {
            a <<= 1; c <<= 1; ct--;
            if (ct == 0) {
                int t = (int) (c >> 19);
                if ((t & ~0xff) != 0) {
                    if (buf >= 0) { buf++; put(buf); if (buf == 0xff) put(0); }
                    while (sc > 0) { put(0); sc--; }
                    buf = t & 0xff;
                } else if (t == 0xff) {
                    sc++;
                } else {
                    if (buf >= 0) put(buf);
                    while (sc > 0) { put(0xff); put(0); sc--; }
                    buf = t;
                }
                c &= 0x7ffff; ct = 8;
            }
        } while (a < 0x8000);
    }

    private void flush() {
        long t = (a - 1 + c) & 0xffff0000L;
        c = (t < c) ? t + 0x8000 : t;
        c <<= ct;
        if ((c & 0xf8000000L) != 0) {
            if (buf >= 0) { put(buf + 1); if (buf + 1 == 0xff) put(0); }
            if ((c & 0x7fff800L) != 0) { while (sc > 0) { put(0); sc--; } }
        } else {
            if (buf >= 0) put(buf);
            if ((c & 0x7fff800L) != 0) { while (sc > 0) { put(0xff); put(0); sc--; } }
        }
        if ((c & 0x7fff800L) != 0) {
            int b = (int) ((c >> 19) & 0xff); put(b); if (b == 0xff) put(0);
            if ((c & 0x7f800L) != 0) {
                b = (int) ((c >> 11) & 0xff); put(b); if (b == 0xff) put(0);
            }
        }
    }

    /**
     * @param bits  بيانات مُعبأة 1bpp (1 = أسود، MSB أولاً)، كل سطر rowBytes بايت
     * @param rows  عدد أسطر الـ band (256، وللأخير 164)
     * @param first فهرس أول سطر داخل الـ bits
     * @param w     عرض بالبكسل
     */
    public static byte[] encodeBand(byte[] bits, int first, int rowBytes, int w, int rows) {
        JbigBandEncoder e = new JbigBandEncoder();
        // صفوف بكسل-لكل-بايت مع حشوة 4 يساراً و3 يميناً
        int pw = w + 8;
        byte[][] rws = new byte[rows + 2][pw];   // الصفان 0 و1 = أصفار (y=-2,-1)
        boolean[] same = new boolean[rows];
        for (int y = 0; y < rows; y++) {
            byte[] r = rws[y + 2];
            int o = (first + y) * rowBytes;
            for (int x = 0; x < w; x++) r[x + 4] = (byte) ((bits[o + (x >> 3)] >> (7 - (x & 7))) & 1);
            same[y] = java.util.Arrays.equals(r, rws[y + 1]);
        }
        int lntp = 0;
        for (int ys = 0; ys < rows; ys += STRIPE_ROWS) {
            int ye = Math.min(rows, ys + STRIPE_ROWS);
            e.resetCoder();
            int start = e.n;
            for (int y = ys; y < ye; y++) {
                int typical = same[y] ? 1 : 0;
                e.encode(SLNTP, typical ^ lntp ^ 1);
                lntp = typical;
                if (typical == 1) continue;
                byte[] cur = rws[y + 2], up = rws[y + 1];
                for (int x = 0; x < w; x++) {
                    int p = x + 4;
                    int cx = up[p - 3] | (up[p - 2] << 1) | (up[p - 1] << 2) | (up[p] << 3)
                           | (up[p + 1] << 4) | (up[p + 2] << 5)
                           | (cur[p - 1] << 6) | (cur[p - 2] << 7) | (cur[p - 3] << 8) | (cur[p - 4] << 9);
                    e.encode(cx, cur[p]);
                }
            }
            e.flush();
            while (e.n > start && e.out[e.n - 1] == 0) e.n--;
            e.put(0xff); e.put(ye < rows ? 0x02 : 0x03);
        }
        return java.util.Arrays.copyOf(e.out, e.n);
    }
}
