# Maven Local Repository Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复项目级 Maven 仓库路径解析错误，保留项目内仓库设计，同时消除误生成的 `${session.executionRootDirectory}` 目录。

**Architecture:** 只修改 `.mvn/settings.xml` 的 `localRepository` 路径表达式，改为 Maven 稳定可解析的项目根目录变量；随后删除误生成的字面量目录。最后通过 Maven 实际解析结果和目录检查验证修复。

**Tech Stack:** Maven, PowerShell, Windows filesystem

---

### Task 1: 修复本地仓库配置并清理异常目录

**Files:**
- Modify: `.mvn/settings.xml`
- Create: `docs/superpowers/plans/2026-04-09-maven-local-repo-fix.md`

- [ ] **Step 1: 修改 `.mvn/settings.xml` 的本地仓库路径**

将：

```xml
<localRepository>${session.executionRootDirectory}\ .mvn\repository</localRepository>
```

改为：

```xml
<localRepository>${maven.multiModuleProjectDirectory}/.mvn/repository</localRepository>
```

- [ ] **Step 2: 删除误生成的异常目录**

运行：

```powershell
$target = Resolve-Path -LiteralPath '.\${session.executionRootDirectory}'
if ($target.Path -ne 'E:\projects\QuillLoom\${session.executionRootDirectory}') { throw 'unexpected delete target' }
Remove-Item -LiteralPath $target.Path -Recurse -Force
```

- [ ] **Step 3: 验证 Maven 实际解析到正确本地仓库**

运行：

```powershell
mvn -q help:evaluate "-Dexpression=settings.localRepository" "-DforceStdout"
```