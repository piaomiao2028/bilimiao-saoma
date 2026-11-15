# 哔哩喵扫码（Bilimiao Scan Mod）

给 [哔哩喵（Bilimiao）](https://github.com/10miaomiao/bilimiao2) 用的 Xposed / LSPosed 模块：
在哔哩喵里加一个扫码入口，扫到的东西直接跳进哔哩喵看。

> 目标应用包名：`com.a10miaomiao.bilimiao`
> 模块包名：`cn.a10miaomiao.bilimiao.scan`

## 功能

- **底栏扫码图标**：和「首页 / 动态 / 搜索」同排，20dp 图标 + 「扫码」小字，颜色跟随深浅色主题；位置按底栏原生图标行/文字行对齐。
- **宿主扫码入口兼容**：哔哩喵搜索框旁边那个相机图标走的是原版 `bilimiao.plugin.ACTION_SCAN_QRCODE`；装了插件就用插件，没装则自动兜底到模块自带扫码页。
- **相机扫码（模块自带）**：相机预览 + ZXing 解码都在模块自己的 Activity 里跑，**不需要再装 bilimiao_scanner 插件**（装了也只是回退路径）。
- **相册识别**：长按按钮从相册选图，进程内用 ZXing 解码，截图里的二维码也能认（4 个方向 + 反色重试）。
- **扫码登录**：扫到登录二维码时直接用哔哩喵当前账号确认（网页登录 `qrcode_key` / TV 登录 `auth_code` 两种都支持）。
- **结果落地**：`BV号` / `av号` / `b23.tv` 短链 / `bilibili://`、`bilimiao://` 链接，统一转成哔哩喵的 deeplink 打开；同时把原文复制到剪贴板。

## 安装

1. 手机装好哔哩喵（`com.a10miaomiao.bilimiao`）。
2. 装本模块 APK。
3. 在 LSPosed 里启用模块，作用域勾选哔哩喵（模块内已用 `xposedscope` 声明）。
4. 强制停止哔哩喵再打开，底栏右侧会出现扫码图标。

## 构建

不需要 Gradle，纯命令行（需要 JDK 17 + Android build-tools + platform android.jar）：

```bash
./build.sh
```

脚本做的事：`javac`(release 8) → 桩类打 lib jar → `d8`（含 `libs/zxing-core.jar`）→ `aapt2 compile/link` → 打包 dex/assets/module.prop → `zipalign` → `apksigner`。

路径常量在 `build.sh` 顶部（`JDK` / `BT` / `ANDROID_JAR`），按自己机器改一下即可。

## 目录

```
src/cn/a10miaomiao/bilimiao/scan/
├── ScanModule.java    # 入口：hook 生命周期、挂按钮、分派结果
├── ScanButton.java    # 自绘按钮（圆底 / 纯图标两种形态）
├── QrDecoder.java     # 相册图片二维码解码（ZXing）
└── LoginConfirm.java  # 扫码登录确认（复用哔哩喵 cookie）
stubs/                 # XposedBridge / IXposedHookLoadPackage 等编译期桩类
libs/zxing-core.jar    # ZXing core 3.5.3
assets/xposed_init     # 模块入口类名
META-INF/xposed/module.prop
```

## 实现要点

- 挂载点用 `Instrumentation.callActivityOnResume`，只在哔哩喵首页类 Activity 上挂一次（tag 去重）。
- 按钮加到 `android.R.id.content` 这个 FrameLayout，`bottomMargin = 系统导航栏 inset + 4dp`，所以能贴在底栏那一排。
- 主界面的顶栏是 Compose 自绘的（`AppBarConfig` 70dp，右侧菜单在 `LazyRow` 里），没有 View 可插，所以只能做成「覆盖在底栏图标行」的形式。
- `XC_LoadPackage` 必须写成 `de.robv.android.xposed.callbacks.XC_LoadPackage`，包名写错会导致接口方法没实现、hook 静默失效。
- 相册解码走 SAF（`ACTION_OPEN_DOCUMENT`），不需要存储权限；图片先按 `inSampleSize` 缩到 1600px 以内再识别，避免 OOM。

## 已知限制

- 只适配哔哩喵当前版本的首页结构，换大版本后按钮位置可能需要微调。
- 扫码登录依赖哔哩喵自己的 cookie（`PlatformProviders.cookieProvider`），未登录时无法确认。
- 相册解码对「二维码占画面很小」的图识别率一般，建议截屏后先裁剪。

## 致谢

- [bilimiao_scanner](https://github.com/10miaomiao/bilimiao_scanner) —— 扫码插件本体
- [Bilimiao](https://github.com/10miaomiao/bilimiao2) —— 宿主应用
- [ZXing](https://github.com/zxing/zxing) —— 图像二维码解码

## License

GPL-3.0（与 Bilimiao 保持一致）。
