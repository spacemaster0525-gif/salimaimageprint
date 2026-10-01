package com.salimaprint.app.render;

/**
 * إزالة البقع السوداء المعزولة (ضوضاء/شعار باهت متحوّل إلى نقاط) من صفحة أحادية اللون.
 * تُحذف المكوّنات الصغيرة المتصلة فقط إذا كانت معزولة: لا يوجد أي حبر آخر ضمن مسافة
 * (RX, RY) حولها. لذلك تبقى نقاط الحروف العربية والفواصل لأنها قريبة من جسم الحرف.
 * لا تعتمد على أندرويد.
 */
public final class Despeckle {
    private Despeckle() {}

    public static final int MAX_AREA = 500;  // أقصى مساحة (بكسل) للبقعة المحذوفة
    public static final int MAX_DIM  = 60;   // أقصى عرض/ارتفاع للبقعة
    public static final int RX = 80;         // مسافة العزل أفقياً (600 dpi)
    public static final int RY = 55;         // مسافة العزل عمودياً (400 dpi)

    private static boolean get(byte[] b, int rb, int x, int y) {
        return (b[y * rb + (x >> 3)] & (0x80 >> (x & 7))) != 0;
    }
    private static void clear(byte[] b, int rb, int x, int y) {
        b[y * rb + (x >> 3)] &= (byte) ~(0x80 >> (x & 7));
    }
    private static void set(byte[] b, int rb, int x, int y) {
        b[y * rb + (x >> 3)] |= (byte) (0x80 >> (x & 7));
    }

    /** @return عدد البقع المحذوفة */
    public static int run(byte[] bits, int rb, int w, int h) {
        byte[] seen = new byte[bits.length];
        int[] stack = new int[1 << 16];
        int[] comp = new int[MAX_AREA + 1];
        int removed = 0;
        for (int y0 = 0; y0 < h; y0++) {
            for (int x0 = 0; x0 < w; x0++) {
                if (!get(bits, rb, x0, y0) || get(seen, rb, x0, y0)) continue;
                int sp = 0, cnt = 0, total = 0;
                int minX = x0, maxX = x0, minY = y0, maxY = y0;
                stack[sp++] = y0 * w + x0; set(seen, rb, x0, y0);
                while (sp > 0) {
                    int c = stack[--sp];
                    int cx = c % w, cy = c / w;
                    total++;
                    if (cnt <= MAX_AREA) comp[cnt] = c;
                    cnt++;
                    if (cx < minX) minX = cx; if (cx > maxX) maxX = cx;
                    if (cy < minY) minY = cy; if (cy > maxY) maxY = cy;
                    for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = cx + dx, ny = cy + dy;
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                        if (!get(bits, rb, nx, ny) || get(seen, rb, nx, ny)) continue;
                        set(seen, rb, nx, ny);
                        if (sp == stack.length) stack = java.util.Arrays.copyOf(stack, sp * 2);
                        stack[sp++] = ny * w + nx;
                    }
                }
                if (total > MAX_AREA || maxX - minX >= MAX_DIM || maxY - minY >= MAX_DIM) continue;
                for (int i = 0; i < total; i++) clear(bits, rb, comp[i] % w, comp[i] / w);
                boolean isolated = true;
                int ax = Math.max(0, minX - RX), bx = Math.min(w - 1, maxX + RX);
                int ay = Math.max(0, minY - RY), by = Math.min(h - 1, maxY + RY);
                outer:
                for (int y = ay; y <= by; y++) {
                    int b0 = ax >> 3, b1 = bx >> 3;
                    for (int bi = b0; bi <= b1; bi++) {
                        if (bits[y * rb + bi] != 0) { isolated = false; break outer; }
                    }
                }
                if (isolated) removed++;
                else for (int i = 0; i < total; i++) set(bits, rb, comp[i] % w, comp[i] / w);
            }
        }
        return removed;
    }
}
