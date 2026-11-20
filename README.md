# 小豆通知（Xiaodou Notify）· Android 原生 App

**小豆通知是「毛豆通知」的无成就轻量独立版**：通知核心能力完全一致，但移除整套成就系统——没有成就定义、进度记录、达成判定，也没有成就入口。适合只想要安静的通知管家、不需要游戏化激励的用户。

- **技术栈**：Kotlin + Jetpack Room + Material 3 + 协程 + ViewBinding
- **版本**：**v1.0.0**（versionCode 1）· 最低 Android 8.0（API 26），目标 Android 15（API 35）
- **隐私**：通知内容 AES-256-GCM 加密存本地（密钥在 Android Keystore），零上传；唯一外发通道是用户显式配置的 Webhook
- **包名**：`com.xiaodou.notify`（与毛豆通知不同，可同时安装、数据互不干扰）

## 与毛豆通知的功能边界

| 能力 | 毛豆通知 | 小豆通知 |
|---|---|---|
| 通知历史 / 搜索 / 倒叙 | ✅ | ✅ |
| 自定义过滤规则 / 离线 AI 骚扰识别 | ✅ | ✅ |
| 验证码保险箱 / 清净周报 / 桌面小部件 | ✅ | ✅ |
| 主题 / 图标切换 / 应用锁 | ✅ | ✅ |
| 离线 Pro 激活（open-core） | ✅ | ✅ |
| 成就分类 / 定义 / 进度 | ✅ | ❌ 未包含 |
| 成就达成判定 / 奖励领取 | ✅ | ❌ 未包含 |
| 底部导航「成就」入口 | ✅ | ❌ 未包含 |

## 目录结构

```
XiaodouNotify/
├── app/src/main/java/com/xiaodou/notify/
│   ├── XiaodouApp.kt          # Application：主题、应用锁、保活、默认规则
│   ├── data/                  # Room 数据库 v2：通知/规则/AI 反馈（无成就表）
│   ├── engine/                # 规则引擎、骚扰评分、动作执行、Webhook
│   ├── listener/              # 通知监听服务、保活、小部件、广播接收器
│   ├── ui/                    # 历史 / 规则 / 分析 / 设置 四个 tab 与各详情页
│   └── util/                  # 权限、应用锁、Pro、主题、偏好等工具
├── app/src/main/res/
│   ├── layout/                # 仅基础通知页面（无成就布局）
│   ├── menu/bottom_nav_menu.xml  # 四个入口，无 nav_achievements
│   └── values/                # 应用名「小豆通知」，无成就字符串
├── app/build.gradle.kts       # applicationId=com.xiaodou.notify，versionCode=1
└── README.md
```

## 构建

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew assembleRelease
```

公开仓库为 **open-core 社区版**：`util/ProHelper.kt` 是社区占位实现（校验恒不通过），`app/build.gradle.kts` 不注入任何密钥，clone 后可直接编译出完整免费版。需要 Pro 请使用官方发布包并向开发者索取激活码。

## 开源说明

本仓库不含成就系统代码。如需要成就功能，请使用同一作者的「毛豆通知」：https://github.com/qiangsi/maodou-notify-android
