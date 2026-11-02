# DexKit 按需驻留补丁

基于 LuckyPray/DexKit 固定提交 `76551eb`，遵循 LGPL-3.0-or-later 许可。`DexKitBridge.kt` 保留原版权声明，其余本地补丁使用相同许可。

## 生命周期

- `createManaged(path, idleTimeoutMillis, onIdleRelease)` 只记录 APK 身份，实际 native 查询才加载。
- Hchat 共享入口最后一次查询结束后空闲 30 秒，后台释放 native 分析资源；轻量 Java 桥接对象及已定位 Method/Class 保留。
- 所有 native 查询与回收经同一可重入锁，禁止关闭在途查询；后续功能和旧结果懒加载按需恢复。
- 永久 `close()` 不可恢复；线程数和最大并发设置在重新打开时重放。旧工厂方法保持原有语义。
- 固定上游 `Core/dexkit/dexkit.cpp` 的 `AddZipPath` 按 classes.dex、classes2.dex 顺序分配索引，线程执行前捕获 index，并非按线程完成顺序分配。因此同一输入的旧结果编号稳定。
- 恢复前核对 APK 大小、时间及每个 DEX 的 CRC；输入被替换时拒绝复用旧结果。
- 每个入口最多一个回收任务，任务只弱引用入口，所有入口共用一个回收线程。
- 回收回调输出 `[Hchat:DexMemory]`、PID 和当时 native 堆占用至 LSPosed 日志；不承诺固定节省数值。

## 可复现构建与验证

```sh
python3 scripts/build_dexkit_memory_aar.py
node scripts/run_dex_idle_memory_tests.cjs
python3 scripts/run_dex_bridge_integration_tests.py
```

原始输入为 app/libs/dexkit-2.2.0-76551eb.aar，输出为 app/libs/dexkit-2.2.0-76551eb-idle.aar。仅重编本目录 Kotlin 桥接层，保留其余原类、模块元数据、规则及全部 native 库。构建脚本核对旧公开 API 与各 .so 的 SHA-256。

JNI 回归运行的是新 AAR 的真实桥接和查询结果类，只替代宿主无法运行的 Android JNI 边界。它证明释放与恢复的调用链和兼容性，不替代手机 PSS/RSS 或实际功能测试。不通过关闭功能、强制 GC 或伪造统计制造降幅。
