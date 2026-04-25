# 2026-04-13 D 残留命名修订与首次命名确认计划

## 目标

只在 D 相关链路内补两项能力，不改其他 prompt：

1. 第 1 阶段结束后，检查正文里残留的外文命名是否已在当前翻译词池中；若已在词池中但正文未采用，必须通过 issue 提醒第 2 阶段修正。
2. 对尚未进入当前生效译名表的高频核心人名，D 本轮无论决定翻成中文还是决定保留原文，都必须把该决定写入 `confirmedTermUpdates`，让后续 chunk 稳定沿用。

## 修改范围

- Update: `src/main/java/io/quillloom/infrastructure/translation/TranslationPromptRenderer.java`
- Update: `src/main/java/io/quillloom/infrastructure/translation/GlossaryComplianceIssueDetector.java`
- Update: `src/main/java/io/quillloom/infrastructure/translation/ChunkTranslationResultValidator.java`
- Update: `src/test/java/io/quillloom/infrastructure/translation/GlossaryComplianceIssueDetectorTest.java`
- Update: `src/test/java/io/quillloom/infrastructure/translation/TranslationPromptRendererTest.java`
- Update: `src/test/java/io/quillloom/infrastructure/translation/ChunkTranslationResultValidatorTest.java`
- Update: `src/test/java/io/quillloom/application/translation/service/TranslationApplicationServiceTest.java`
- Update: `docs/handoff.md`

## 实现思路

### 1. 第 1 阶段后残留命名校验

- 词池来源统一视为：
  - `confirmedTerms`
  - `DraftStageGlobalGlossary.hardEntries`
  - `DraftStageGlobalGlossary.softEntries`
- 若正文仍残留 source term，且词池中已有对应 target term，则输出：
  - `name-residue-warning`
  - `glossary-entry-not-applied`
- 这些 issue 会进入第 2 阶段 revision 输入，驱动修订。

### 2. 首次命名必须登记

- 在 D 第 1 阶段 prompt 中明确：
  - 若高频核心人名尚未进入当前生效译名表，本轮无论决定翻成中文还是保留原文，都必须写入 `confirmedTermUpdates`
  - 不允许正文采用了一个稳定叫法，却不登记
- validator 增加受控校验：
  - 当 chunk `entities` 中的核心名字不在当前词池，但正文出现了该 source term 或其稳定译法时，要求本轮 `confirmedTermUpdates` 至少登记一个决定
  - 该规则先保守限定在“人名型高频核心实体 + 当前 chunk 明确出现”的场景，避免误伤普通术语

## 验证

- `GlossaryComplianceIssueDetectorTest`
  - 覆盖词池内已有项但正文未应用时，revision issue 能被产出
- `TranslationPromptRendererTest`
  - 覆盖“首次命名必须登记 confirmedTermUpdates”的 prompt 文案
- `ChunkTranslationResultValidatorTest`
  - 覆盖高频核心人名未登记时会被补 issue
- `TranslationApplicationServiceTest`
  - 覆盖首轮登记后，后续 chunk 输入可在 `confirmedTerms` 中稳定沿用

