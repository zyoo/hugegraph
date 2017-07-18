package com.baidu.hugegraph.api.filter;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.zip.GZIPOutputStream;

import javax.inject.Singleton;
import javax.ws.rs.NameBinding;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.Provider;
import javax.ws.rs.ext.WriterInterceptor;
import javax.ws.rs.ext.WriterInterceptorContext;

@Provider
@Singleton
@CompressInterceptor.Compress
public class CompressInterceptor implements WriterInterceptor {

    public static final String GZIP = "gzip";

    // Set compress output buffer size to 4KB (about 40~600 vertices)
    public static final int BUFFER_SIZE = 1024 * 4;

    @Override
    public void aroundWriteTo(WriterInterceptorContext context)
                              throws IOException, WebApplicationException {
        // Get compress info from the @Compress annotation
        final Compress compression = getCompressAnnotation(context);
        final String encoding = compression.value();
        final int buffer = compression.buffer();

        // Update header
        MultivaluedMap<String,Object> headers = context.getHeaders();
        headers.remove("Content-Length");
        headers.add("Content-Encoding", encoding);

        // Replace output stream with new compression stream
        OutputStream output = null;
        if (encoding.equalsIgnoreCase(GZIP)) {
            output = new GZIPOutputStream(context.getOutputStream(), buffer);
        } else {
            /*
             * NOTE: Currently we just support GZIP.
             * FIXME: This will cause java.lang.IllegalStateException:
             *   Illegal attempt to call getOutputStream() after getWriter()
             */
            throw new WebApplicationException("Can't support: " + encoding);
        }
        context.setOutputStream(output);

        context.proceed();
    }

    private static Compress getCompressAnnotation(WriterInterceptorContext c) {
        for (Annotation annotation : c.getAnnotations()) {
            if (annotation.annotationType() == Compress.class) {
                return (Compress) annotation;
            }
        }
        throw new AssertionError("Unable find @Compress annotation");
    }

    @NameBinding
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Compress {
        String value() default GZIP;
        int buffer() default BUFFER_SIZE;
    }
}