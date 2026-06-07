package android.print;

import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;

import java.io.File;

public final class ActMePrintBridge {
    private ActMePrintBridge() {
    }

    public interface Callback {
        void onSuccess(File file);

        void onError(Throwable error);
    }

    public static void print(
            PrintDocumentAdapter adapter,
            PrintAttributes attributes,
            File pdfFile,
            Callback callback
    ) {
        adapter.onLayout(
                attributes,
                attributes,
                new CancellationSignal(),
                new PrintDocumentAdapter.LayoutResultCallback() {
                    @Override
                    public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                        final ParcelFileDescriptor descriptor;
                        try {
                            File parent = pdfFile.getParentFile();
                            if (parent != null) {
                                //noinspection ResultOfMethodCallIgnored
                                parent.mkdirs();
                            }
                            descriptor = ParcelFileDescriptor.open(
                                    pdfFile,
                                    ParcelFileDescriptor.MODE_CREATE
                                            | ParcelFileDescriptor.MODE_TRUNCATE
                                            | ParcelFileDescriptor.MODE_READ_WRITE
                            );
                        } catch (Throwable error) {
                            finish(adapter);
                            callback.onError(error);
                            return;
                        }

                        adapter.onWrite(
                                new PageRange[]{PageRange.ALL_PAGES},
                                descriptor,
                                new CancellationSignal(),
                                new PrintDocumentAdapter.WriteResultCallback() {
                                    @Override
                                    public void onWriteFinished(PageRange[] pages) {
                                        close(descriptor);
                                        finish(adapter);
                                        callback.onSuccess(pdfFile);
                                    }

                                    @Override
                                    public void onWriteFailed(CharSequence error) {
                                        close(descriptor);
                                        finish(adapter);
                                        callback.onError(new RuntimeException(
                                                error != null ? error.toString() : "WebView PDF write failed."
                                        ));
                                    }

                                    @Override
                                    public void onWriteCancelled() {
                                        close(descriptor);
                                        finish(adapter);
                                        callback.onError(new RuntimeException("WebView PDF write cancelled."));
                                    }
                                }
                        );
                    }

                    @Override
                    public void onLayoutFailed(CharSequence error) {
                        finish(adapter);
                        callback.onError(new RuntimeException(
                                error != null ? error.toString() : "WebView PDF layout failed."
                        ));
                    }

                    @Override
                    public void onLayoutCancelled() {
                        finish(adapter);
                        callback.onError(new RuntimeException("WebView PDF layout cancelled."));
                    }
                },
                new Bundle()
        );
    }

    private static void close(ParcelFileDescriptor descriptor) {
        try {
            descriptor.close();
        } catch (Throwable ignored) {
            // Ignore close failure after the print pipeline has already completed.
        }
    }

    private static void finish(PrintDocumentAdapter adapter) {
        try {
            adapter.onFinish();
        } catch (Throwable ignored) {
            // Some platform implementations may already be finished.
        }
    }
}
