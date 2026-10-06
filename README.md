# dsh-pet 手机版（Android 悬浮桌宠）

把 [PC2005-cloud/dsh-pet](https://github.com/PC2005-cloud/dsh-pet) 的桌宠搬到 Android：一个透明悬浮窗，
浮在微信/桌面之上，可拖动、可点、可甩 —— **渲染逻辑一行未改地复用插件自己的**
`runtime/electron-helper/{shared-core,constants,sprite,events,renderer}.js`。

## 它是怎么工作的

| Electron 桌面版 | 这个 App |
| --- | --- |
| 主进程 `BrowserWindow` | `PetService` 里的 `WindowManager` 悬浮窗（`TYPE_APPLICATION_OVERLAY`） |
| `setContentBounds(rect)` | `updateViewLayout(params.x/y/width/height)` |
| `pet:set-bounds`（IPC） | `PetBridge.setBounds`（`addJavascriptInterface`） |
| 注入显示器工作区（URL query） | 同样用 URL query：`workAreaX/Y/W/H` |
| `setZoomFactor(scale)` | `WebView.setInitialScale(scale*100)` |
| 宿主 HTTP 服务（3080） | `LocalServer`：只绑 `127.0.0.1`，把 APK 内素材按**同样的 URL 结构**回给 WebView |

坐标换算：配置里的 `size`（462）是桌面 CSS 像素，手机 DPI 高得多，所以引入
`unit = 物理像素 / CSS 单位`，让宠物约占屏幕短边的 30%；渲染端上报的窗口矩形乘 `unit` 即物理像素。

## 构建 / 安装

仓库自带 GitHub Actions：push 到 `main` 就会构建，并把 APK 发到 **Releases**（手机浏览器点链接直接装）。

本地构建（需要 JDK 17 + Android SDK；本项目不含 gradle wrapper，用系统 gradle 8.7+）：

```sh
gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

安装后：打开 App → ① 授予「显示在其他应用上层」→ ② 显示桌宠。

## 当前版本（v0.1）能力与限制

- ✅ 悬浮在其它应用之上、拖动、点击/甩抛反应、全部 106 个动画与表情包（打包在 APK 内，共约 60MB）
- ✅ 完全离线：素材走本地服务，不联网
- ⛔ 碎碎念 / 对话 / 余额：首版关掉了（`assets/pet/config.json` 里 `whisperEnabled/balanceEnabled = false`）。
  下一步可以接上电脑上的独立模式宿主（局域网），key 留在电脑上
- ⛔ 右键级联菜单：手机没有右键（后续可做长按菜单）
- ⚠️ 手机省电策略可能在长时间不用后回收悬浮窗；宠物消失时回到 App 再点一次「显示桌宠」

## 素材与许可

代码部分来自 [PC2005-cloud/dsh-pet](https://github.com/PC2005-cloud/dsh-pet)（MIT）。
**动画 / 表情包等美术素材**由该项目提供，其授权为「允许开源使用，**禁止商用**」——
本仓库仅作个人学习与开源使用，请勿用于商业用途。详见 `NOTICE.md`。
