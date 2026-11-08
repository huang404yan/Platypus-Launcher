# 贡献约定

## 最重要的一条：不抄 GPL 代码

本项目的参照对象里，HMCL、Prism Launcher、ATLauncher、PCL-CE、PojavLauncher、FCL 都是 **GPL-3.0**。PCL2 是专有许可。从这些项目复制代码会让本项目被迫整体 GPL，或直接侵权。

允许：
- 读它们的文档、issue、公开讨论，理解它们**怎么解决问题**
- 借鉴功能设计、交互流程、错误文案思路
- 使用它们公开的**协议/格式标准**（如 authlib-injector 协议、Modrinth API、mrpack 格式）

不允许：
- 复制、粘贴、改写它们的源码
- 逐行翻译它们的实现
- 把它们的资源文件（图标、字体、翻译）直接拿来用

新依赖的要求：MIT / Apache-2.0 / BSD / MPL-2.0 可直接用；GPL/AGPL 依赖必须在 PR 里说明理由并单独评审。

## 代码

- Kotlin 官方代码风格
- `core` 不依赖 UI、不依赖 Android
- 公共 API 先写接口，再写实现
- 新增模块前先在 issue / 文档里写清边界

## 提交前

```bash
./gradlew build
```

## 许可证

提交即表示同意以 Apache-2.0 授权。
