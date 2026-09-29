package com.salimaprint.app.render;

/**
 * تسوية الإضاءة لصور المستندات (صور كاميرا لورقة) قبل التحويل إلى أبيض/أسود.
 * تُقدِّر "لون الورقة" محلياً (نسبة مئوية عالية داخل كل خانة) ثم يُقسم كل بكسل عليه،
 * فتصبح الظلال والزوايا المعتمة بيضاء بدل أن تُطبع سواداً، ويبقى النص أسود.
 * لا تعتمد على أندرويد كي يمكن اختبارها على أي JVM.
 */
public final class DocFlatten {
    private static final int LONG_SIDE_CELLS = 14;   // عدد الخانات على الضلع الأطول
    private static final float PERCENTILE = 0.75f;   // "الورقة" = المئين 75 داخل الخانة
    public static final float MIN_BG = 48f;          // لا نقسم على قيمة أصغر من هذا

    private final int gw, gh;
    private final float[] grid;          // خلفية الخانات بعد التنعيم
    private final float cell;            // حجم الخانة بالبكسل (في الصورة المصغّرة)
    private final int sw, sh;            // أبعاد الصورة المصغّرة
    private final float offY, drawH;
    private final int[] ix0;             // فهرس الخانة اليسرى لكل عمود من الصفحة
    private final float[] wx;            // وزن التداخل الأفقي

    /**
     * @param argb  بكسلات الصورة المصغّرة (ARGB)
     * @param sw,sh أبعادها
     * @param pageW عرض الصفحة بالبكسل، offX/drawW موضع الصورة داخل الصفحة أفقياً
     * @param offY/drawH موضعها عمودياً
     */
    public DocFlatten(int[] argb, int sw, int sh, int pageW, float offX, float drawW, float offY, float drawH) {
        this.sw = sw; this.sh = sh; this.offY = offY; this.drawH = drawH;
        int longSide = Math.max(sw, sh);
        this.cell = Math.max(4f, longSide / (float) LONG_SIDE_CELLS);
        this.gw = Math.max(1, (int) Math.ceil(sw / cell));
        this.gh = Math.max(1, (int) Math.ceil(sh / cell));

        // رمادي مع دمج الشفافية على الأبيض
        float[] gray = new float[sw * sh];
        for (int i = 0; i < gray.length; i++) {
            int p = argb[i];
            float a = ((p >>> 24) & 0xff) / 255f;
            float g = ((p >> 16) & 0xff) * 0.3f + ((p >> 8) & 0xff) * 0.59f + (p & 0xff) * 0.11f;
            gray[i] = g * a + 255f * (1f - a);
        }

        float[] raw = new float[gw * gh];
        int[] hist = new int[256];
        for (int gy = 0; gy < gh; gy++) {
            for (int gx = 0; gx < gw; gx++) {
                java.util.Arrays.fill(hist, 0);
                int x0 = (int) (gx * cell), x1 = Math.min(sw, (int) ((gx + 1) * cell + 0.5f));
                int y0 = (int) (gy * cell), y1 = Math.min(sh, (int) ((gy + 1) * cell + 0.5f));
                int n = 0;
                for (int y = y0; y < y1; y++)
                    for (int x = x0; x < x1; x++) { hist[Math.min(255, Math.max(0, (int) gray[y * sw + x]))]++; n++; }
                float v = 255f;
                if (n > 0) {
                    int target = (int) (n * PERCENTILE), acc = 0;
                    for (int k = 0; k < 256; k++) { acc += hist[k]; if (acc > target) { v = k; break; } }
                }
                raw[gy * gw + gx] = v;
            }
        }
        // تنعيم 3×3 مرة واحدة
        grid = new float[gw * gh];
        for (int gy = 0; gy < gh; gy++)
            for (int gx = 0; gx < gw; gx++) {
                float s = 0; int c = 0;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = gx + dx, yy = gy + dy;
                        if (xx < 0 || yy < 0 || xx >= gw || yy >= gh) continue;
                        s += raw[yy * gw + xx]; c++;
                    }
                grid[gy * gw + gx] = s / c;
            }

        // فهارس/أوزان التداخل الأفقي لكل عمود من الصفحة (تُحسب مرة واحدة)
        ix0 = new int[pageW]; wx = new float[pageW];
        for (int x = 0; x < pageW; x++) {
            float u = ((x + 0.5f) - offX) / drawW;                 // 0..1 داخل الصورة
            float gxf = Math.min(Math.max(u * sw / cell - 0.5f, 0f), gw - 1);
            int i0 = (int) gxf;
            ix0[x] = Math.min(i0, Math.max(0, gw - 2));
            wx[x] = gw == 1 ? 0f : gxf - ix0[x];
        }
    }

    /** يملأ out[x] بقيمة خلفية الورقة عند سطر الصفحة pageY لكل x. */
    public void row(int pageY, float[] out) {
        float v = ((pageY + 0.5f) - offY) / drawH;
        float gyf = Math.min(Math.max(v * sh / cell - 0.5f, 0f), gh - 1);
        int j0 = Math.min((int) gyf, Math.max(0, gh - 2));
        float wy = gh == 1 ? 0f : gyf - j0;
        int j1 = gh == 1 ? j0 : j0 + 1;
        for (int x = 0; x < out.length; x++) {
            int i0 = ix0[x], i1 = gw == 1 ? i0 : i0 + 1;
            float top = grid[j0 * gw + i0] * (1 - wx[x]) + grid[j0 * gw + i1] * wx[x];
            float bot = grid[j1 * gw + i0] * (1 - wx[x]) + grid[j1 * gw + i1] * wx[x];
            float b = top * (1 - wy) + bot * wy;
            out[x] = b < MIN_BG ? MIN_BG : b;
        }
    }
}
