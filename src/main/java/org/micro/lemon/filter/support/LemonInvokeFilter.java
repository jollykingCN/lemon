package org.micro.lemon.filter.support;

import org.micro.lemon.common.LemonInvoke;
import org.micro.lemon.common.LemonStatusCode;
import org.micro.lemon.common.LemonConfig;
import org.micro.lemon.filter.AbstractFilter;
import org.micro.lemon.filter.LemonChain;
import org.micro.lemon.filter.LemonFactory;
import org.micro.lemon.server.LemonContext;
import lombok.extern.slf4j.Slf4j;
import org.micro.neural.extension.Extension;
import org.micro.neural.extension.ExtensionLoader;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Lemon Invoke Filter
 *
 * @author lry
 */
@Slf4j
@Extension(value = "invoke", order = 200, category = LemonFactory.ROUTER)
public class LemonInvokeFilter extends AbstractFilter {

    private final ConcurrentMap<String, LemonInvoke> lemonInvokes = new ConcurrentHashMap<>();

    @Override
    public void initialize(LemonConfig lemonConfig) {
        List<LemonInvoke> lemonInvokeList = ExtensionLoader.getLoader(LemonInvoke.class).getExtensions();
        for (LemonInvoke lemonInvoke : lemonInvokeList) {
            Extension extension = lemonInvoke.getClass().getAnnotation(Extension.class);
            if (extension != null) {
                lemonInvokes.put(extension.value(), lemonInvoke);
                lemonInvoke.initialize(lemonConfig);
            }
        }
    }

    @Override
    public void preFilter(LemonChain chain, LemonContext context) throws Throwable {
        LemonInvoke lemonInvoke = lemonInvokes.get(context.getProtocol());
        if (lemonInvoke == null) {
            context.writeAndFlush(LemonStatusCode.NOT_FOUND);
            return;
        }

        CompletableFuture<Object> future = lemonInvoke.invokeAsync(context);
        if (future == null) {
            log.error("The completable future is null by context:{}", context);
            return;
        }

        future.whenComplete((result, throwable) -> {
            context.setReceiveTime(System.currentTimeMillis());
            if (throwable != null) {
                log.error(throwable.getMessage(), throwable);
                LemonStatusCode statusCode = lemonInvoke.failure(context, throwable);
                context.writeAndFlush(statusCode);
                return;
            } else {
                context.setResult(result);
            }

            try {
                super.preFilter(chain, context);
            } catch (Throwable t) {
                log.error(t.getMessage(), t);
            } finally {
                context.writeAndFlush(LemonStatusCode.SUCCESS);
            }
        });
    }

    @Override
    public void destroy() {
        for (ConcurrentMap.Entry<String, LemonInvoke> entry : lemonInvokes.entrySet()) {
            entry.getValue().destroy();
        }
    }

}
