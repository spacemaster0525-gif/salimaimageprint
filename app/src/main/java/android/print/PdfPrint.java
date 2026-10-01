package android.print;

import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;

import java.io.File;

/**
 * يحوّل PrintDocumentAdapter (مثل الذي يعطيه WebView) إلى ملف PDF.
 * يجب أن يكون الصنف داخل الحزمة android.print لأن منشئات LayoutResultCallback و
 * WriteResultCallback غير عامة. يُستدعى من الخيط الرئيسي فقط.
 */
public final class PdfPrint {
    public interface Callback {
        void onDone(File pdf);
        void onError(String message);
    }

    private final PrintAttributes attributes;

    public PdfPrint(PrintAttributes attributes) {
        this.attributes = attributes;
    }

    public void print(final PrintDocumentAdapter adapter, final File out, final Callback cb) {
        try {
            adapter.onStart();
            adapter.onLayout(null, attributes, new CancellationSignal(),
                new PrintDocumentAdapter.LayoutResultCallback() {
                    @Override
                    public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                        final ParcelFileDescriptor fd;
                        try {
                            fd = ParcelFileDescriptor.open(out,
                                ParcelFileDescriptor.MODE_READ_WRITE
                                    | ParcelFileDescriptor.MODE_CREATE
                                    | ParcelFileDescriptor.MODE_TRUNCATE);
                        } catch (Exception e) {
                            cb.onError("open: " + e.getMessage());
                            return;
                        }
                        adapter.onWrite(new PageRange[]{PageRange.ALL_PAGES}, fd, new CancellationSignal(),
                            new PrintDocumentAdapter.WriteResultCallback() {
                                @Override
                                public void onWriteFinished(PageRange[] pages) {
                                    try { fd.close(); } catch (Exception ignored) { }
                                    try { adapter.onFinish(); } catch (Exception ignored) { }
                                    cb.onDone(out);
                                }

                                @Override
                                public void onWriteFailed(CharSequence error) {
                                    try { fd.close(); } catch (Exception ignored) { }
                                    cb.onError("write: " + error);
                                }

                                @Override
                                public void onWriteCancelled() {
                                    try { fd.close(); } catch (Exception ignored) { }
                                    cb.onError("write cancelled");
                                }
                            });
                    }

                    @Override
                    public void onLayoutFailed(CharSequence error) {
                        cb.onError("layout: " + error);
                    }

                    @Override
                    public void onLayoutCancelled() {
                        cb.onError("layout cancelled");
                    }
                }, null);
        } catch (Throwable t) {
            cb.onError(String.valueOf(t.getMessage()));
        }
    }
}
