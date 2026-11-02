package h.Hchat.hooks.api.core;

import h.Hchat.hooks.api.runtime.WeChatTaskApi;

public final class WeChatApis {
    private static final Runtime RUNTIME = new Runtime();
    public static Runtime runtime() { return RUNTIME; }
    public static void useTasks(WeChatTaskApi tasks) { RUNTIME.tasks = tasks; }
    public static final class Runtime {
        private WeChatTaskApi tasks;
        public WeChatTaskApi tasks() { return tasks; }
    }
}
