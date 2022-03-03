package io.matrix;

import java.net.http.HttpResponse;

public interface CompletionEvent {
    boolean onCompletion(HttpResponse<String> res);
}
