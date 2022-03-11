package io.matrix;

import java.net.http.HttpResponse;

public interface FailureEvent {
    boolean onFailure(Throwable res);
}
