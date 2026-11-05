package com.axcore.platform.action.handler;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** 서버에 등록된 {@link ActionHandler}. 같은 키를 가진 Handler가 둘이면 어느 쪽을 실행할지 알 수 없어 시작하지 않는다. */
@Component
public class ActionHandlerRegistry {

    private final Map<String, ActionHandler> handlers;

    public ActionHandlerRegistry(List<ActionHandler> handlers) {
        this.handlers =
                handlers.stream()
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        ActionHandler::key,
                                        Function.identity(),
                                        (first, second) -> {
                                            throw new IllegalStateException(
                                                    "같은 키를 가진 Action Handler가 둘입니다: "
                                                            + first.key());
                                        }));
    }

    public boolean isRegistered(String key) {
        return handlers.containsKey(key);
    }

    public Optional<ActionHandler> find(String key) {
        return Optional.ofNullable(key).map(handlers::get);
    }
}
