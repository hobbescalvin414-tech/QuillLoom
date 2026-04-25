# 运行与排障

本文档只回答三个问题：

1. 如何跑基础验证
2. 如何阅读 `run-output`
3. 遇到问题先看哪里

## 1. 常用验证入口

### 定向测试

常用命令示例：

```powershell
mvn -q "-Dtest=WorkflowTraceArtifactWriterTest,NovelTranslationWorkflowServiceTraceTest" test
mvn -q "-Dtest=RuleBasedKnowledgeRetrievalServiceTest" test
mvn -q "-Dtest=LlmKnowledgeNeedPlannerTest,KnowledgeSearchGateTest" test
mvn -q -DskipTests compile
```

### 全链路样例

```powershell
mvn -q "-Dtest=BookWorkflowSampleSmokeTest" "-Dquillloom.test.book-workflow-sample.enabled=true" test
```

注意：

1. 样例冒烟依赖本地 PostgreSQL。
2. 若数据库不可连，Spring 上下文会在初始化阶段失败。
3. 不要把环境失败误判成业务链路失败。

## 2. `run-output` 目录怎么读

推荐阅读顺序：

1. `00-run-overview.txt`
2. `30-annotations-readable.txt`
3. `40-c0-readable.txt`
4. `50-translation-input-readable.txt`
5. `60-draft-readable.txt`

机器产物仍保留：

1. `00-manifest.json`
2. `01-events.ndjson`
3. 各阶段 `.json`
4. 旧的逐事件 `.txt`

原则：

1. 人先看 readable 文件。
2. 需要精确定位字段时，再回到 json/ndjson。

## 3. 看什么来判断链路是否健康

### Annotate

重点看：

1. `entities`
2. `backgroundQuestions`
3. `translationRisks`
4. `keyExpressions`

若这些信号质量差，后续 C0 与 D 基本都会被拖偏。

### C0

重点看：

1. 哪些 chunk 触发了知识搜索
2. planned needs 是什么
3. 哪些 need 被 gate 放行
4. 建出来什么卡
5. 拒绝建卡的原因是什么

### 装配层与 D

重点看：

1. 首批选卡是否过多或过少
2. confirmed terms 是否合理
3. 哪些 chunk 触发了本地补卡
4. draft commentary 是否反映真实知识缺口

## 4. 常见问题与优先排查路径

### 没有知识卡

先查：

1. annotate 是否给出有效信号
2. C0 的 need 是否被 planner 生成
3. gate 是否因预算或覆盖而拦截
4. 搜索或 organizer 是否把结果拒绝掉

### 选卡不准

先查：

1. 检索 query 是否组装正确
2. 场景策略是否正确使用 `ASSEMBLY / SUPPLEMENTAL_LOOKUP`
3. 类型偏好与限额是否把关键卡挤掉

### 样例跑不起来

先查：

1. PostgreSQL 是否启动
2. 连接串是否正确
3. schema 初始化是否成功
4. dev 配置中的模型和搜索配置是否可用
