package dev.deskflow;

import java.io.*;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    final CompletableFuture<byte[]> result=new CompletableFuture<>();
    final ByteArrayOutputStream output=new ByteArrayOutputStream();
    Flow.Subscription subscription;
    @Override public CompletionStage<byte[]> getBody(){return result;}
    @Override public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
    @Override public void onNext(List<ByteBuffer> buffers){
        for(var buffer:buffers){
            if(output.size()+buffer.remaining()>65536){subscription.cancel();result.completeExceptionally(new IOException("Response exceeds 64 KB"));return;}
            byte[] bytes=new byte[buffer.remaining()];buffer.get(bytes);output.writeBytes(bytes);
        }
        subscription.request(1);
    }
    @Override public void onError(Throwable error){result.completeExceptionally(error);}
    @Override public void onComplete(){result.complete(output.toByteArray());}
}
