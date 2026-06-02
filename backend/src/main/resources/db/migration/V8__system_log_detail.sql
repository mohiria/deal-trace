-- view-system-log: 系统日志结构化 detail 载荷（spec system-log ADDED / design D1）
-- 纯加列、可空、无回填：结构化增补前写入的旧行 detail=NULL 属合法状态，读出侧回退 freetext summary。
-- detail 存稳定引用（归属 ownerId 非 email、阶段枚举码、精确金额字符串、原因码），供读出侧组装展示。
-- 不查询 detail 内部，故不加索引；全局页筛选仅按已索引的 action / target_type。
ALTER TABLE system_log ADD COLUMN detail JSON NULL AFTER summary;
