package com.salimaprint.app.render;

/**
 * تسوية الإضاءة لصور المستندات (صور كاميرا لورقة) قبل التحويل إلى أبيض/أسود.
 *
 * 1) تُقدَّر "خلفية الورقة" لكل خانة (المئين 75 داخل الخانة).
 * 2) الخانات المعتمة جداً قياساً إلى الورقة (طاولة، ظل كثيف، ما بعد حافة الورقة) تُعدّ "خارج الورقة"
 *    وتُطبع بيضاء، ولا تدخل في حساب خلفية الورقة (فلا تلوّث الخانات المجاورة).
 * 3) داخل الورقة يُقسم كل بكسل على خلفيته المحلية فتصبح الظلال الخفيفة بيضاء ويبقى الحبر أسود.
 * لا تعتمد على أندرويد كي يمكن اختبارها على أي JVM.
 */
public final class DocFlatten {
    private static final int LONG_SIDE_CELLS = 24;   // عدد الخانات على الضلع الأطول
    private static final float PERCENTILE = 0.75f;   // "الورقة" = المئين 75 داخل الخانة
    private static final float REF_PERCENTILE = 0.90f; // مرجع سطوع الورقة = المئين 90 بين الخانات
    private static final float PAPER_MIN_FRAC = 0.40f; // خانة أقل من 40% من المرجع = خارج الورقة
    public static final float MIN_BG = 48f;

    private final int gw, gh;
    private final float[] cellBg;        // خلفية كل خانة
    private final float[] isPaper;       // 1 داخل الورقة، 0 خارجها
    private final float cell;
    private final int sw, sh;
    private final float offY, drawH;
    private final int[] ix0;
    private final float[] wx;

    public DocFlatten(int[] argb, int sw, int sh, int pageW, float offX, float drawW, float offY, float drawH) {
        this.sw = sw; this.sh = sh; this.offY = offY; this.drawH = drawH;
        int longSide = Math.max(sw, sh);
        this.cell = Math.max(4f, longSide / (float) LONG_SIDE_CELLS);
        this.gw = Math.max(1, (int) Math.ceil(sw / cell));
        this.gh = Math.max(1, (int) Math.ceil(sh / cell));

        float[] gray = new float[sw * sh];
        for (int i = 0; i < gray.length; i++) {
            int p = argb[i];
            float a = ((p >>> 24) & 0xff) / 255f;
            float g = ((p >> 16) & 0xff) * 0.3f + ((p >> 8) & 0xff) * 0.59f + (p & 0xff) * 0.11f;
            gray[i] = g * a + 255f * (1f - a);
        }

        cellBg = new float[gw * gh];
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
                cellBg[gy * gw + gx] = v;
            }
        }

        // مرجع سطوع الورقة = المئين 90 بين قيم الخانات
        float[] sorted = cellBg.clone();
        java.util.Arrays.sort(sorted);
        float ref = sorted[Math.min(sorted.length - 1, (int) (sorted.length * REF_PERCENTILE))];
        ref = Math.max(ref, MIN_BG);
        isPaper = new float[gw * gh];
        boolean[] dark = new boolean[gw * gh];
        for (int i = 0; i < dark.length; i++) dark[i] = cellBg[i] < PAPER_MIN_FRAC * ref;

        // الخانات المعتمة التي تتصل بحدّ الصورة = خارج الورقة (طاولة/ظل). أما المعتمة المحاطة بالورقة
        // من كل جانب (شريط أسود، شعار، مربع) فهي حبر: تبقى داخل الورقة وتُعطى خلفية جيرانها.
        boolean[] outside = new boolean[gw * gh];
        int[] queue = new int[gw * gh];
        int qh = 0, qt = 0;
        for (int gy = 0; gy < gh; gy++)
            for (int gx = 0; gx < gw; gx++)
                if ((gx == 0 || gy == 0 || gx == gw - 1 || gy == gh - 1) && dark[gy * gw + gx]) {
                    outside[gy * gw + gx] = true; queue[qt++] = gy * gw + gx;
                }
        while (qh < qt) {
            int c = queue[qh++], cx = c % gw, cy = c / gw;
            int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] d : nb) {
                int nx = cx + d[0], ny = cy + d[1];
                if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue;
                int n = ny * gw + nx;
                if (dark[n] && !outside[n]) { outside[n] = true; queue[qt++] = n; }
            }
        }
        boolean[] known = new boolean[gw * gh];
        for (int i = 0; i < known.length; i++) {
            isPaper[i] = outside[i] ? 0f : 1f;
            known[i] = !dark[i];
        }
        // خلفية الخانات المعتمة المحاطة بالورقة: متوسط الجيران المعروفين (انتشار تدريجي)
        boolean changed = true;
        while (changed) {
            changed = false;
            boolean[] add = new boolean[gw * gh];
            float[] val = new float[gw * gh];
            for (int gy = 0; gy < gh; gy++)
                for (int gx = 0; gx < gw; gx++) {
                    int i = gy * gw + gx;
                    if (known[i] || outside[i]) continue;
                    float sum = 0; int cnt = 0;
                    for (int dy = -1; dy <= 1; dy++)
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = gx + dx, ny = gy + dy;
                            if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue;
                            int n = ny * gw + nx;
                            if (known[n]) { sum += cellBg[n]; cnt++; }
                        }
                    if (cnt > 0) { add[i] = true; val[i] = sum / cnt; }
                }
            for (int i = 0; i < add.length; i++) if (add[i]) { cellBg[i] = val[i]; known[i] = true; changed = true; }
        }

        ix0 = new int[pageW]; wx = new float[pageW];
        for (int x = 0; x < pageW; x++) {
            float u = ((x + 0.5f) - offX) / drawW;
            float gxf = Math.min(Math.max(u * sw / cell - 0.5f, 0f), gw - 1);
            int i0 = (int) gxf;
            ix0[x] = Math.min(i0, Math.max(0, gw - 2));
            wx[x] = gw == 1 ? 0f : gxf - ix0[x];
        }
    }

    /**
     * يملأ out[x] بخلفية الورقة عند سطر الصفحة pageY.
     * القيمة -1 تعني: هذه النقطة خارج الورقة، تُطبع بيضاء.
     */
    public void row(int pageY, float[] out) {
        float v = ((pageY + 0.5f) - offY) / drawH;
        float gyf = Math.min(Math.max(v * sh / cell - 0.5f, 0f), gh - 1);
        int j0 = Math.min((int) gyf, Math.max(0, gh - 2));
        float wy = gh == 1 ? 0f : gyf - j0;
        int j1 = gh == 1 ? j0 : j0 + 1;
        float wy0 = 1f - wy, wy1 = wy;
        for (int x = 0; x < out.length; x++) {
            int i0 = ix0[x], i1 = gw == 1 ? i0 : i0 + 1;
            float wxx = wx[x];
            float w00 = (1f - wxx) * wy0, w10 = wxx * wy0, w01 = (1f - wxx) * wy1, w11 = wxx * wy1;
            int a = j0 * gw + i0, b = j0 * gw + i1, c = j1 * gw + i0, d = j1 * gw + i1;
            float pw = w00 * isPaper[a] + w10 * isPaper[b] + w01 * isPaper[c] + w11 * isPaper[d];
            if (pw < 0.5f) { out[x] = -1f; continue; }
            float s = w00 * isPaper[a] * cellBg[a] + w10 * isPaper[b] * cellBg[b]
                    + w01 * isPaper[c] * cellBg[c] + w11 * isPaper[d] * cellBg[d];
            float bg = s / pw;
            out[x] = bg < MIN_BG ? MIN_BG : bg;
        }
    }
}
