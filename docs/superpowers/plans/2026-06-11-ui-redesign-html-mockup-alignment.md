# UI 全面对齐 HTML 设计稿 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Android UI 全面对齐 `资产管理系统HTML界面.html` 设计稿，5 个独立 commit，每阶段可编译。

**Architecture:** 渐进式资源 + 组件化重构。先抽取通用组件 `view_stat_card` / `view_tag_pill` / `view_info_card` / `view_asset_row` 与 `bg_*.xml` 形状资源，再按导入→扫描→结果顺序改造 Fragment，删除 `AssetPreviewAdapter` / `RecentScanAdapter` 与 `viewResultFab` / `previewRv` / `recentRv` / `tabs` / `searchView`。底部导航 emoji 化。

**Tech Stack:** AndroidX ConstraintLayout + Material Components 1.x + Room 2.6 + LiveData。Java 11，minSdk 24。

**Spec:** `docs/superpowers/specs/2026-06-11-ui-redesign-html-mockup-alignment-design.md`

---

## 文件结构总览

### 新建
```
res/values/attrs.xml
res/values/styles.xml
res/layout/view_stat_card.xml
res/layout/view_tag_pill.xml
res/layout/view_info_card.xml
res/layout/view_asset_row.xml
res/menu/menu_main.xml
res/drawable/ic_import.xml
res/drawable/ic_scan.xml
res/drawable/ic_result.xml
res/drawable/ic_export.xml
res/drawable/bg_dashed_border.xml
res/drawable/bg_tag_blue.xml
res/drawable/bg_tag_green.xml
res/drawable/bg_tag_orange.xml
res/drawable/bg_tag_red.xml
res/drawable/bg_tip_box.xml
res/drawable/bg_file_info.xml
```

### 修改
```
res/values/colors.xml
res/values/dimens.xml
res/values/themes.xml
res/values/strings.xml
res/layout/activity_main.xml
res/layout/fragment_import.xml
res/layout/fragment_scan.xml
res/layout/fragment_result.xml
res/menu/bottom_nav.xml
src/.../MainActivity.java
src/.../ui/import_/ImportFragment.java
src/.../ui/scan/ScanFragment.java
src/.../ui/scan/ScanViewModel.java
src/.../ui/result/ResultFragment.java
src/.../ui/result/ResultViewModel.java
src/.../ui/result/AssetResultAdapter.java
src/.../data/dao/AssetDao.java
src/.../data/entity/Asset.java (可能 — 加 status 派生字段或保持不变)
```

### 删除
```
res/layout/item_asset_preview.xml
src/.../ui/import_/AssetPreviewAdapter.java
src/.../ui/scan/RecentScanAdapter.java
res/layout/item_recent_scan.xml
```

---

## 阶段 1：基建（颜色/尺寸/主题/通用组件/AppBar）

### Task 1: 颜色资源更新

**Files:**
- Modify: `app/src/main/res/values/colors.xml`

- [ ] **Step 1: 完整替换 `colors.xml` 内容**

```xml
<resources>
    <color name="colorPrimary">#1976D2</color>
    <color name="colorPrimaryDark">#0D47A1</color>
    <color name="colorAccent">#FF9800</color>
    <color name="statusFound">#2E7D32</color>
    <color name="statusMissing">#C62828</color>
    <color name="statusExtra">#EF6C00</color>
    <color name="bgSurface">#F5F5F5</color>
    <color name="cardCorner">#FFFFFF</color>
    <color name="tagBlueBg">#E3F2FD</color>
    <color name="tagBlueFg">#1565C0</color>
    <color name="tagGreenBg">#E8F5E9</color>
    <color name="tagGreenFg">#2E7D32</color>
    <color name="tagOrangeBg">#FFF3E0</color>
    <color name="tagOrangeFg">#EF6C00</color>
    <color name="tagRedBg">#FFEBEE</color>
    <color name="tagRedFg">#C62828</color>
    <color name="tipBg">#E3F2FD</color>
    <color name="tipFg">#1565C0</color>
    <color name="statScanned">#1565C0</color>
    <color name="statTotal">#9C27B0</color>
    <color name="borderDashed">#BDBDBD</color>
</resources>
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add app/src/main/res/values/colors.xml
git -c user.name="Claude" -c user.email="noreply@anthropic.com" commit -m "feat(ui): 颜色资源对齐设计稿（状态色/标签色/统计色）"
```

---

### Task 2: 尺寸资源更新

**Files:**
- Modify: `app/src/main/res/values/dimens.xml`

- [ ] **Step 1: 完整替换 `dimens.xml` 内容**

```xml
<resources>
    <dimen name="cardMargin">12dp</dimen>
    <dimen name="cardPadding">16dp</dimen>
    <dimen name="cardCornerRadius">12dp</dimen>
    <dimen name="cardElevation">2dp</dimen>
    <dimen name="textSizeTitle">20sp</dimen>
    <dimen name="textSizeBody">14sp</dimen>
    <dimen name="textSizeLabel">12sp</dimen>
    <dimen name="statNumberSize">26sp</dimen>
    <dimen name="tagPaddingH">12dp</dimen>
    <dimen name="tagPaddingV">4dp</dimen>
    <dimen name="bottomNavHeight">56dp</dimen>
    <dimen name="appBarHeight">56dp</dimen>
    <dimen name="uploadAreaHeight">200dp</dimen>
</resources>
```

- [ ] **Step 2: 编译验证 + 提交**

Run: `./gradlew :app:assembleDebug` && `git add ... && git commit -m "feat(ui): 尺寸资源统一（圆角/阴影/统计字号）"`

---

### Task 3: 自定义属性 attrs.xml

**Files:**
- Create: `app/src/main/res/values/attrs.xml`

- [ ] **Step 1: 创建 `attrs.xml`**

```xml
<resources>
    <declare-styleable name="StatCard">
        <attr name="statNumber" format="integer|string" />
        <attr name="statNumberColor" format="color" />
        <attr name="statLabel" format="string" />
        <attr name="statSub" format="string" />
    </declare-styleable>

    <declare-styleable name="TagPill">
        <attr name="tagText" format="string" />
        <attr name="tagBg" format="color" />
        <attr name="tagFg" format="color" />
    </declare-styleable>
</resources>
```

- [ ] **Step 2: 编译 + 提交**

Run: `./gradlew :app:assembleDebug` && `git add ... && git commit -m "feat(ui): 自定义属性 StatCard/TagPill"`

---

### Task 4: 样式 styles.xml

**Files:**
- Create: `app/src/main/res/values/styles.xml`

- [ ] **Step 1: 创建 `styles.xml`**

```xml
<resources>
    <style name="Huaxia.Card" parent="Widget.MaterialComponents.CardView">
        <item name="cardCornerRadius">@dimen/cardCornerRadius</item>
        <item name="cardElevation">@dimen/cardElevation</item>
        <item name="cardBackgroundColor">@color/cardCorner</item>
    </style>

    <style name="Huaxia.Toolbar" parent="Widget.MaterialComponents.Toolbar.Primary">
        <item name="android:background">@color/colorPrimary</item>
        <item name="titleTextColor">@android:color/white</item>
        <item name="subtitleTextColor">@android:color/white</item>
        <item name="android:elevation">4dp</item>
    </style>

    <style name="Huaxia.StatNumber">
        <item name="android:textSize">@dimen/statNumberSize</item>
        <item name="android:textStyle">bold</item>
        <item name="android:gravity">center</item>
    </style>

    <style name="Huaxia.StatLabel">
        <item name="android:textSize">@dimen/textSizeLabel</item>
        <item name="android:textColor">#888888</item>
        <item name="android:gravity">center</item>
    </style>

    <style name="Huaxia.StatSub">
        <item name="android:textSize">11sp</item>
        <item name="android:textColor">#AAAAAA</item>
        <item name="android:gravity">center</item>
    </style>
</resources>
```

- [ ] **Step 2: 编译 + 提交**

Run: `./gradlew :app:assembleDebug` && `git add ... && git commit -m "feat(ui): 共享样式 Huaxia.Card/Toolbar/Stat" `

---

### Task 5: 主题 themes.xml

**Files:**
- Modify: `app/src/main/res/values/themes.xml`

- [ ] **Step 1: 完整替换 `themes.xml`**

```xml
<resources>
    <style name="Theme.HuaxiaInventory" parent="Theme.MaterialComponents.DayNight.NoActionBar">
        <item name="colorPrimary">@color/colorPrimary</item>
        <item name="colorPrimaryDark">@color/colorPrimaryDark</item>
        <item name="colorAccent">@color/colorAccent</item>
        <item name="colorOnPrimary">@android:color/white</item>
        <item name="android:statusBarColor">@color/colorPrimary</item>
        <item name="android:windowLightStatusBar">false</item>
        <item name="toolbarStyle">@style/Huaxia.Toolbar</item>
    </style>
</resources>
```

- [ ] **Step 2: 编译 + 提交**

Run: `./gradlew :app:assembleDebug` && `git add ... && git commit -m "feat(ui): 主题蓝底白字 + 状态栏沉浸"`

---

### Task 6: 通用 drawable（圆角 / 虚线 / 标签背景）

**Files:**
- Create: `app/src/main/res/drawable/bg_dashed_border.xml`
- Create: `app/src/main/res/drawable/bg_tag_blue.xml`
- Create: `app/src/main/res/drawable/bg_tag_green.xml`
- Create: `app/src/main/res/drawable/bg_tag_orange.xml`
- Create: `app/src/main/res/drawable/bg_tag_red.xml`
- Create: `app/src/main/res/drawable/bg_tip_box.xml`
- Create: `app/src/main/res/drawable/bg_file_info.xml`

- [ ] **Step 1: 创建 `bg_dashed_border.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <stroke android:width="2dp"
        android:color="@color/borderDashed"
        android:dashWidth="6dp"
        android:dashGap="4dp" />
    <corners android:radius="@dimen/cardCornerRadius" />
    <solid android:color="#FAFAFA" />
</shape>
```

- [ ] **Step 2: 创建 4 个 tag 背景（以 blue 为例，其他仅改 solid color）**

`bg_tag_blue.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="@color/tagBlueBg" />
    <corners android:radius="999dp" />
</shape>
```

`bg_tag_green.xml`: solid `#E8F5E9` / `bg_tag_orange.xml`: solid `#FFF3E0` / `bg_tag_red.xml`: solid `#FFEBEE`

- [ ] **Step 3: 创建 `bg_tip_box.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="@color/tipBg" />
    <corners android:radius="8dp" />
</shape>
```

- [ ] **Step 4: 创建 `bg_file_info.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="@color/bgSurface" />
    <corners android:radius="8dp" />
</shape>
```

- [ ] **Step 5: 编译 + 提交**

Run: `./gradlew :app:assembleDebug` && `git add ... && git commit -m "feat(ui): 通用 shape 资源（虚线/标签/提示卡/文件信息）"`

---

### Task 7: 底导矢量图标

**Files:**
- Create: `app/src/main/res/drawable/ic_import.xml`
- Create: `app/src/main/res/drawable/ic_scan.xml`
- Create: `app/src/main/res/drawable/ic_result.xml`
- Create: `app/src/main/res/drawable/ic_export.xml`

- [ ] **Step 1: `ic_import.xml`（文件夹+下箭头 24dp）**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path android:fillColor="@android:color/white"
        android:pathData="M20,6h-8l-2,-2H4C2.9,4 2,4.9 2,6v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8C22,6.9 21.1,6 20,6zM20,18H4V6h5.17l2,2H20V18zM12,11l-4,4h3v3h2v-3h3L12,11z" />
</vector>
```

- [ ] **Step 2: `ic_scan.xml`（扫码框 24dp）**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path android:fillColor="@android:color/white"
        android:pathData="M9.5,6.5v3h-3v-3H9.5M11,5H5v6h6V5L11,5zM9.5,14.5v3h-3v-3H9.5M11,13H5v6h6V13L11,13zM17.5,6.5v3h-3v-3H17.5M19,5h-6v6h6V5L19,5zM13,13h1.5v1.5L13,14.5L13,13zM14.5,14.5L16,14.5v1.5h-1.5V14.5zM16,13h1.5v1.5L16,14.5L16,13zM13,16h1.5v1.5L13,17.5L13,16zM14.5,17.5L16,17.5v1.5h-1.5V17.5zM16,16h1.5v1.5L16,17.5L16,16zM17.5,14.5L19,14.5v1.5h-1.5V14.5zM17.5,17.5L19,17.5v1.5h-1.5V17.5zM22,7h-2L20,4h-3L17,2h5v5zM22,22v-5h-2v3h-3v2h5zM2,22h5v-2L4,20v-3L2,17v5zM2,2v5h2L4,4h3L7,2L2,2z" />
</vector>
```

- [ ] **Step 3: `ic_result.xml`（柱状图 24dp）**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path android:fillColor="@android:color/white"
        android:pathData="M19,3H5C3.9,3 3,3.9 3,5v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5C21,3.9 20.1,3 19,3zM19,19H5V5h14V19zM7,10h2v7H7zM11,7h2v10h-2zM15,13h2v4h-2z" />
</vector>
```

- [ ] **Step 4: `ic_export.xml`（下载箭头 24dp）**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24"
    android:tint="@android:color/white">
    <path android:fillColor="@android:color/white"
        android:pathData="M19,9h-4V3H9v6H5l7,7L19,9zM5,18v2h14v-2H5z" />
</vector>
```

- [ ] **Step 5: 编译 + 提交**

Run: `./gradlew :app:assembleDebug` && `git add ... && git commit -m "feat(ui): 底导 + 导出矢量图标"`

---

### Task 8: 通用组件 view_stat_card.xml

**Files:**
- Create: `app/src/main/res/layout/view_stat_card.xml`

- [ ] **Step 1: 创建 `view_stat_card.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<merge xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto">

    <androidx.cardview.widget.CardView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        app:cardCornerRadius="@dimen/cardCornerRadius"
        app:cardElevation="@dimen/cardElevation"
        app:cardBackgroundColor="@color/cardCorner"
        android:clickable="true"
        android:focusable="true"
        android:foreground="?attr/selectableItemBackground">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:padding="14dp"
            android:gravity="center">

            <TextView
                android:id="@+id/statLabelTv"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                style="@style/Huaxia.StatLabel" />

            <TextView
                android:id="@+id/statNumberTv"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp"
                style="@style/Huaxia.StatNumber" />

            <TextView
                android:id="@+id/statSubTv"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="2dp"
                android:visibility="gone"
                style="@style/Huaxia.StatSub" />
        </LinearLayout>
    </androidx.cardview.widget.CardView>
</merge>
```

- [ ] **Step 2: 创建包装 wrapper `view_stat_card_wrapper.xml`（用 `<include>` 容器更易复用）**

由于 `<merge>` 必须放在 include 容器里，我们改用 wrapper 方式：直接创建 `view_stat_card.xml` 不带 merge，作为独立组件 inflate 使用。重新写：

`view_stat_card.xml` (重写，无 merge):
```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.cardview.widget.CardView
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:cardCornerRadius="@dimen/cardCornerRadius"
    app:cardElevation="@dimen/cardElevation"
    app:cardBackgroundColor="@color/cardCorner"
    android:clickable="true"
    android:focusable="true"
    android:foreground="?attr/selectableItemBackground">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="14dp"
        android:gravity="center">

        <TextView
            android:id="@+id/statLabelTv"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            style="@style/Huaxia.StatLabel" />

        <TextView
            android:id="@+id/statNumberTv"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="4dp"
            style="@style/Huaxia.StatNumber" />

        <TextView
            android:id="@+id/statSubTv"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="2dp"
            android:visibility="gone"
            style="@style/Huaxia.StatSub" />
    </LinearLayout>
</androidx.cardview.widget.CardView>
```

- [ ] **Step 3: 编译 + 提交**

Run: `./gradlew :app:assembleDebug` && `git add ... && git commit -m "feat(ui): 通用统计卡 view_stat_card"`

---

### Task 9: 通用组件 view_tag_pill.xml

**Files:**
- Create: `app/src/main/res/layout/view_tag_pill.xml`

- [ ] **Step 1: 创建 `view_tag_pill.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<TextView
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/tagPillTv"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:background="@drawable/bg_tag_blue"
    android:paddingStart="@dimen/tagPaddingH"
    android:paddingEnd="@dimen/tagPaddingH"
    android:paddingTop="@dimen/tagPaddingV"
    android:paddingBottom="@dimen/tagPaddingV"
    android:textSize="@dimen/textSizeLabel"
    android:textColor="@color/tagBlueFg"
    android:textStyle="bold" />
```

注意：背景与文字色后续在 Java 中按 type 切换（blue/green/orange/red）。

- [ ] **Step 2: 编译 + 提交**

Run: `./gradlew :app:assembleDebug` && `git add ... && git commit -m "feat(ui): 通用标签药丸 view_tag_pill"`

---

### Task 10: 通用组件 view_info_card.xml

**Files:**
- Create: `app/src/main/res/layout/view_info_card.xml`

- [ ] **Step 1: 创建 `view_info_card.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.cardview.widget.CardView
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginTop="10dp"
    app:cardCornerRadius="@dimen/cardCornerRadius"
    app:cardElevation="@dimen/cardElevation"
    app:cardBackgroundColor="@color/cardCorner">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="@dimen/cardPadding">

        <TextView
            android:id="@+id/infoTitleTv"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textSize="16sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/infoDescTv"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:textSize="@dimen/textSizeBody"
            android:textColor="#666666"
            android:lineSpacingExtra="2dp" />

        <TextView
            android:id="@+id/infoSubTv"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:textSize="13sp"
            android:textColor="@color/colorPrimary"
            android:visibility="gone" />

        <LinearLayout
            android:id="@+id/infoTagsContainer"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:layout_marginTop="10dp" />
    </LinearLayout>
</androidx.cardview.widget.CardView>
```

- [ ] **Step 2: 编译 + 提交**

Run: `./gradlew :app:assembleDebug` && `git add ... && git commit -m "feat(ui): 通用说明卡 view_info_card"`

---

### Task 11: 通用组件 view_asset_row.xml（替代 item_asset_result）

**Files:**
- Create: `app/src/main/res/layout/view_asset_row.xml`
- Modify: `app/src/main/res/layout/item_asset_result.xml`（删除 — 见 Task 12 阶段 4 适配）

- [ ] **Step 1: 创建 `view_asset_row.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:background="@color/cardCorner"
    android:padding="14dp"
    android:layout_marginBottom="8dp">

    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:orientation="vertical">

        <TextView
            android:id="@+id/assetCodeTv"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textSize="14sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/assetNameTv"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="2dp"
            android:textSize="13sp"
            android:textColor="#666666" />

        <TextView
            android:id="@+id/assetOrgTv"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textSize="12sp"
            android:textColor="#999999" />
    </LinearLayout>

    <TextView
        android:id="@+id/assetStatusPill"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:background="@drawable/bg_tag_green"
        android:paddingStart="@dimen/tagPaddingH"
        android:paddingEnd="@dimen/tagPaddingH"
        android:paddingTop="@dimen/tagPaddingV"
        android:paddingBottom="@dimen/tagPaddingV"
        android:textSize="@dimen/textSizeLabel"
        android:textStyle="bold"
        android:textColor="@color/tagGreenFg" />
</LinearLayout>
```

- [ ] **Step 2: 编译 + 提交**

Run: `./gradlew :app:assembleDebug` && `git add ... && git commit -m "feat(ui): 通用资产行 view_asset_row"`

---

### Task 12: AppBar + activity_main 改造

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Create: `app/src/main/res/menu/menu_main.xml`

- [ ] **Step 1: 创建 `menu_main.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto">
    <item
        android:id="@+id/action_export"
        android:title="@string/action_export"
        android:icon="@drawable/ic_export"
        app:showAsAction="always" />
</menu>
```

- [ ] **Step 2: 完整替换 `activity_main.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <com.google.android.material.appbar.AppBarLayout
        android:id="@+id/appBar"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:background="@color/colorPrimary"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent">

        <com.google.android.material.appbar.MaterialToolbar
            android:id="@+id/toolbar"
            android:layout_width="match_parent"
            android:layout_height="?attr/actionBarSize"
            android:background="@color/colorPrimary"
            app:title="@string/app_brand"
            app:titleTextColor="@android:color/white" />
    </com.google.android.material.appbar.AppBarLayout>

    <FrameLayout
        android:id="@+id/fragmentContainer"
        android:layout_width="0dp"
        android:layout_height="0dp"
        app:layout_constraintTop_toBottomOf="@id/appBar"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintBottom_toTopOf="@id/bottomNav" />

    <com.google.android.material.bottomnavigation.BottomNavigationView
        android:id="@+id/bottomNav"
        android:layout_width="0dp"
        android:layout_height="@dimen/bottomNavHeight"
        android:background="@color/cardCorner"
        app:menu="@menu/bottom_nav"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintBottom_toBottomOf="parent" />
</androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] **Step 3: 编译 + 提交**

Run: `./gradlew :app:assembleDebug` && `git add ... && git commit -m "feat(ui): activity_main 加 AppBar + 蓝底 Toolbar"`

---

### Task 13: strings.xml 增补

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: 在 `</resources>` 前追加**

```xml
    <string name="app_brand">📦 资产管家</string>
    <string name="nav_import">📥 导入</string>
    <string name="nav_scan">🔍 扫描</string>
    <string name="nav_result">📊 结果</string>
    <string name="import_title">📥 导入资产数据</string>
    <string name="import_upload_hint">拖拽或 点击浏览</string>
    <string name="import_upload_format">支持 .xlsx / .xls</string>
    <string name="import_tip">💡 请确保 Excel 中包含「设备名称」「设备类型」「归口部门」「序列号」「固资编号」等列。</string>
    <string name="import_clear">清除</string>
    <string name="import_file_selected">已选择：%1$s</string>
    <string name="scan_title">🔍 扫描管理</string>
    <string name="scan_stat_in_stock">在库设备</string>
    <string name="scan_stat_scanned">已盘点</string>
    <string name="scan_stat_anomaly">异常</string>
    <string name="scan_stat_sub_today">+%1$d 今日</string>
    <string name="scan_info_in_stock">📍 在库设备</string>
    <string name="scan_info_in_stock_desc">当前存放于仓库/办公区的全部资产，包含未盘点与已盘点设备。</string>
    <string name="scan_info_checked">📌 已盘点设备</string>
    <string name="scan_info_checked_desc">已完成扫码盘点的设备清单，包含盘点人、时间及状态。</string>
    <string name="scan_info_checked_sub">⏱ 最后盘点：%1$s</string>
    <string name="scan_info_recent">📋 最近盘点结果</string>
    <string name="scan_info_recent_desc">正常 %1$d 台，盘亏 %2$d 台，盘盈 %3$d 台。</string>
    <string name="scan_start_inventory">➕ 开始盘点</string>
    <string name="scan_device_status">设备状态：%1$s</string>
    <string name="result_title">📊 盘点结果</string>
    <string name="result_stat_all">全部设备</string>
    <string name="result_stat_found">已盘</string>
    <string name="result_stat_missing">盘亏</string>
    <string name="result_stat_extra">盘盈</string>
    <string name="result_pagination">共 %1$d 条，显示 %2$d-%3$d</string>
    <string name="action_export">导出</string>
    <string name="action_prev">上一页</string>
    <string name="action_next">下一页</string>
    <string name="err_parse_failed">Excel 解析失败：%1$s</string>
    <string name="err_empty_result">暂无数据</string>
    <string name="err_export_failed">导出失败：%1$s</string>
    <string name="action_goto_import">去导入</string>
    <string name="msg_export_done">已导出</string>
    <string name="tag_found">已盘</string>
    <string name="tag_missing">盘亏</string>
    <string name="tag_extra">盘盈</string>
    <string name="tag_format_count">%1$s %2$d</string>
```

- [ ] **Step 2: 编译 + 提交**

Run: `./gradlew :app:assembleDebug` && `git add ... && git commit -m "feat(ui): strings 增补品牌/导航/三页文案/错误提示"`

---

### Task 14: MainActivity 加 setSupportActionBar

**Files:**
- Modify: `app/src/main/java/com/huaxia/inventory/MainActivity.java`

- [ ] **Step 1: 读取现有 MainActivity 全文**

Run: `cat app/src/main/java/com/huaxia/inventory/MainActivity.java`

- [ ] **Step 2: 改造 onCreate 加 setSupportActionBar**

定位 `onCreate` 方法，在 `setContentView` 之后追加：

```java
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_export) {
                Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragmentContainer);
                if (current instanceof ResultFragment) {
                    ((ResultFragment) current).onExportClicked();
                    return true;
                }
            }
            return false;
        });
```

- [ ] **Step 3: 编译 + 提交**

Run: `./gradlew :app:assembleDebug` && `git add ... && git commit -m "feat(ui): MainActivity 注入 Toolbar + 导出 menu 路由"`

---

### Task 15: 阶段 1 集成验证

- [ ] **Step 1: 跑全量单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全绿（UI 改动不影响业务测试）

- [ ] **Step 2: lint 检查**

Run: `./gradlew :app:lintDebug`
Expected: 0 error, warning 数量较改动前不显著增加

- [ ] **Step 3: 模拟器/真机视觉确认**

启动 App，确认：
- 顶部蓝底白字 "📦 资产管家" 可见
- 底部 3 tab 可见（"导入"/"扫描"/"结果"）
- 当前 3 个 fragment 仍能正常切换（即便布局还是旧版）
- 切换底导 tab 时 fragment 容器切换正常

如未配置模拟器/真机，此步可标记为"待真机验证"。

---

## 阶段 2：导入页

### Task 16: 删除 AssetPreviewAdapter 与 item_asset_preview

**Files:**
- Delete: `app/src/main/java/com/huaxia/inventory/ui/import_/AssetPreviewAdapter.java`
- Delete: `app/src/main/res/layout/item_asset_preview.xml`

- [ ] **Step 1: 编译验证删除后无引用**

Run: `grep -r "AssetPreviewAdapter\|item_asset_preview" app/src/`
Expected: 仅 `import_/ImportFragment.java` 中可能有引用（已记入下个 task）

- [ ] **Step 2: 删除文件**

```bash
git rm app/src/main/java/com/huaxia/inventory/ui/import_/AssetPreviewAdapter.java
git rm app/src/main/res/layout/item_asset_preview.xml
```

- [ ] **Step 3: 暂不提交（继续下个 task 与 ImportFragment 一起提交）**

---

### Task 17: fragment_import.xml 完整重写

**Files:**
- Modify: `app/src/main/res/layout/fragment_import.xml`

- [ ] **Step 1: 完整替换 `fragment_import.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<ScrollView
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/bgSurface">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="@dimen/cardPadding">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginBottom="16dp"
            android:text="@string/import_title"
            android:textSize="@dimen/textSizeTitle"
            android:textStyle="bold" />

        <FrameLayout
            android:id="@+id/uploadArea"
            android:layout_width="match_parent"
            android:layout_height="@dimen/uploadAreaHeight"
            android:background="@drawable/bg_dashed_border"
            android:clickable="true"
            android:focusable="true"
            android:foreground="?attr/selectableItemBackground">

            <LinearLayout
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:gravity="center"
                android:layout_gravity="center">

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="📂"
                    android:textSize="54sp" />

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="8dp"
                    android:text="@string/import_upload_hint"
                    android:textSize="@dimen/textSizeBody"
                    android:textColor="#888888" />

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="4dp"
                    android:text="@string/import_upload_format"
                    android:textSize="@dimen/textSizeLabel"
                    android:textColor="#AAAAAA" />
            </LinearLayout>
        </FrameLayout>

        <LinearLayout
            android:id="@+id/fileInfoRow"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:background="@drawable/bg_file_info"
            android:padding="12dp"
            android:orientation="horizontal"
            android:gravity="center_vertical"
            android:visibility="gone">

            <TextView
                android:id="@+id/fileNameTv"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:textSize="@dimen/textSizeBody" />

            <TextView
                android:id="@+id/clearBtn"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/import_clear"
                android:textColor="@color/statusMissing"
                android:padding="4dp"
                android:clickable="true"
                android:focusable="true" />
        </LinearLayout>

        <com.google.android.material.button.MaterialButton
            android:id="@+id/startBtn"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="@string/import_start"
            android:enabled="false" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:background="@drawable/bg_tip_box"
            android:padding="12dp"
            android:orientation="horizontal">

            <TextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="@string/import_tip"
                android:textSize="13sp"
                android:textColor="@color/tipFg" />
        </LinearLayout>
    </LinearLayout>
</ScrollView>
```

- [ ] **Step 2: 暂不提交（与下个 task 一起）**

---

### Task 18: ImportFragment.java 改造

**Files:**
- Modify: `app/src/main/java/com/huaxia/inventory/ui/import_/ImportFragment.java`

- [ ] **Step 1: 完整替换 `ImportFragment.java`**

```java
package com.huaxia.inventory.ui.import_;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.button.MaterialButton;
import com.huaxia.inventory.InventoryApp;
import com.huaxia.inventory.R;

public class ImportFragment extends Fragment {

    private ImportViewModel vm;
    private MaterialButton startBtn;
    private View uploadArea, fileInfoRow;
    private TextView fileNameTv, clearBtn;
    private Uri pickedUri;
    private String pickedFileName;
    private java.util.List<com.huaxia.inventory.data.entity.Asset> currentAssets;

    private final ActivityResultLauncher<String[]> filePicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                pickedUri = uri;
                requireContext().getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                pickedFileName = queryFileName(uri);
                vm.parseFile(uri, pickedFileName);
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_import, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        super.onViewCreated(v, s);
        vm = new ViewModelProvider(this).get(ImportViewModel.class);
        startBtn = v.findViewById(R.id.startBtn);
        uploadArea = v.findViewById(R.id.uploadArea);
        fileInfoRow = v.findViewById(R.id.fileInfoRow);
        fileNameTv = v.findViewById(R.id.fileNameTv);
        clearBtn = v.findViewById(R.id.clearBtn);

        uploadArea.setOnClickListener(view -> filePicker.launch(new String[]{
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"}));
        clearBtn.setOnClickListener(view -> clearSelection());

        startBtn.setOnClickListener(btn -> {
            if (currentAssets == null || currentAssets.isEmpty()) return;
            String fileName = pickedFileName;
            vm.commitToTask(fileName, currentAssets, taskId -> {
                ((InventoryApp) requireActivity().getApplication()).session.setCurrentTaskId(taskId);
                requireActivity().runOnUiThread(() -> {
                    com.google.android.material.bottomnavigation.BottomNavigationView nav =
                            requireActivity().findViewById(R.id.bottomNav);
                    nav.setSelectedItemId(R.id.nav_scan);
                });
            });
        });

        vm.parsedFile().observe(getViewLifecycleOwner(), pf -> {
            if (pf == null) return;
            fileInfoRow.setVisibility(View.VISIBLE);
            fileNameTv.setText(getString(R.string.import_file_selected, pf.fileName));
            currentAssets = vm.lastParsedAssets;
            startBtn.setEnabled(pf.successCount > 0);
        });

        vm.parseError().observe(getViewLifecycleOwner(), err -> {
            if (err == null) return;
            Toast.makeText(requireContext(),
                    getString(R.string.err_parse_failed, err),
                    Toast.LENGTH_LONG).show();
        });
    }

    private void clearSelection() {
        pickedUri = null;
        pickedFileName = null;
        currentAssets = null;
        fileInfoRow.setVisibility(View.GONE);
        startBtn.setEnabled(false);
    }

    private String queryFileName(Uri uri) {
        try (Cursor c = requireContext().getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        }
        return uri.getLastPathSegment();
    }
}
```

- [ ] **Step 2: ImportViewModel 加 `parseError` LiveData（如果还没有）**

打开 `ImportViewModel.java`，如果没有 `parseError` LiveData 字段，按以下模式添加：

```java
private final MutableLiveData<String> parseError = new MutableLiveData<>();
public LiveData<String> parseError() { return parseError; }
```

并在 `parseFile` 失败时 `parseError.postValue(e.getMessage())`。

(具体改动以现有 `ImportViewModel` 字段为准；若已存在 `_error` 等价字段，重命名统一。)

- [ ] **Step 3: 编译 + 提交**

Run: `./gradlew :app:assembleDebug` && `git add ... && git commit -m "feat(ui): 导入页对齐设计稿 — 虚线大上传区 + tip box，去掉预览"`

---

### Task 19: 阶段 2 验证

- [ ] **Step 1: 单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全绿

- [ ] **Step 2: 真机验证（如果可用）**

启动 App → 导入页：
- 看到虚线大上传区 + "📂" + "拖拽或 点击浏览"
- 点击上传区能选文件
- 选完文件后 fileInfoRow 显示文件名 + 清除按钮
- 点击"开始导入"业务逻辑跑通（与之前一致）

---

## 阶段 3：扫描页

### Task 20: AssetDao 新增 countCreatedAfter

**Files:**
- Modify: `app/src/main/java/com/huaxia/inventory/data/dao/AssetDao.java`

- [ ] **Step 1: 在 AssetDao 接口追加**

```java
    @Query("SELECT COUNT(*) FROM Asset WHERE taskId = :taskId")
    int countByTask(long taskId);

    // ↓ 新增 ↓

    @Query("SELECT COUNT(*) FROM Asset WHERE taskId = :taskId AND id IN " +
           "(SELECT id FROM Asset WHERE rowid >= 0) " +
           "AND id IN (SELECT id FROM Asset ORDER BY id DESC LIMIT 1)")
    int countCreatedAfter(long taskId, long sinceMillis);
```

注：精确实现需要 Asset 实体有 `createdAt` 字段。检查 `Asset.java`：

Run: `cat app/src/main/java/com/huaxia/inventory/data/entity/Asset.java | grep -E "createdAt|insertedAt"`

**如果 Asset 已有 `createdAt` 字段**：
```java
    @Query("SELECT COUNT(*) FROM Asset WHERE taskId = :taskId AND createdAt >= :sinceMillis")
    int countCreatedAfter(long taskId, long sinceMillis);
```

**如果 Asset 没有 `createdAt` 字段**：
- 阶段 3 首版可让 `inStockCard` 的"今日"sub 显示 "—" 占位
- 后续迭代加 `createdAt` 字段 + Migration

为简化阶段 3 落地，本计划采用 **首版占位** 策略：DAO 暂不加 `countCreatedAfter`，`ScanFragment` 渲染 "今日" sub 时传 `null` 或写死 0。

- [ ] **Step 2: 编译 + 提交**

Run: `./gradlew :app:assembleDebug` && `git add ... && git commit -m "feat(dao): AssetDao 暂不加 countCreatedAfter（首版占位）"`

如果决定加：`git commit -m "feat(dao): AssetDao.countCreatedAfter"`

---

### Task 21: AssetDao 新增 countByCategory

**Files:**
- Modify: `app/src/main/java/com/huaxia/inventory/data/dao/AssetDao.java`

- [ ] **Step 1: 在 AssetDao 末尾追加**

```java
    @Query("SELECT assetType as category, COUNT(*) as count FROM Asset " +
           "WHERE taskId = :taskId AND assetType IS NOT NULL " +
           "GROUP BY assetType ORDER BY count DESC")
    java.util.List<CategoryCount> countByCategory(long taskId);
```

- [ ] **Step 2: 创建 `CategoryCount` POJO（在 `data/dao/` 包）**

`app/src/main/java/com/huaxia/inventory/data/dao/CategoryCount.java`:
```java
package com.huaxia.inventory.data.dao;

public class CategoryCount {
    public final String category;
    public final int count;
    public CategoryCount(String category, int count) {
        this.category = category;
        this.count = count;
    }
}
```

- [ ] **Step 3: 检查 Asset 实体字段名**

Run: `grep "assetType\|@ColumnInfo" app/src/main/java/com/huaxia/inventory/data/entity/Asset.java | head -20`

如果字段名是 `assetCategory` 或 `category` 而非 `assetType`，相应修改 SQL。如果资产类型字段不存在，阶段 3 首版让 `inStockCard` 的 tag 写死 ["电脑类", "家具类", "仪器类"] 不显示数字。

- [ ] **Step 4: 编译 + 提交**

Run: `./gradlew :app:assembleDebug` && `git add ... && git commit -m "feat(dao): AssetDao.countByCategory"`

---

### Task 22: ScanViewModel 改造（扩展 Stats 与新统计）

**Files:**
- Modify: `app/src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java`

- [ ] **Step 1: 替换 `Stats` 类**

将原 `Stats(expected, found, missing, extra)` 改为：

```java
    public static class Stats {
        public final int inStock;
        public final int scanned;
        public final int anomaly;
        public final int todayAdded;
        public final int found;
        public final int missing;
        public final int extra;
        public final List<CategoryCount> categories;
        public final long lastInventoryAt;
        public Stats(int inStock, int scanned, int anomaly, int todayAdded,
                     int found, int missing, int extra,
                     List<CategoryCount> categories, long lastInventoryAt) {
            this.inStock = inStock; this.scanned = scanned; this.anomaly = anomaly;
            this.todayAdded = todayAdded;
            this.found = found; this.missing = missing; this.extra = extra;
            this.categories = categories;
            this.lastInventoryAt = lastInventoryAt;
        }
    }
```

- [ ] **Step 2: 修改 `recomputeStats` 计算新字段**

```java
    private void recomputeStats() {
        long taskId = currentTaskId;
        long todayStart = startOfTodayMillis();
        diskIO.execute(() -> {
            Set<Long> matched = new HashSet<>();
            List<ScanRecord> recs = db.scanRecordDao().findAllByTask(taskId);
            for (ScanRecord r : recs) {
                if (r.matchedAssetId != null) matched.add(r.matchedAssetId);
            }
            int extras = db.scanRecordDao().findExtras(taskId).size();
            StatusDerivation.Stats s = StatusDerivation.compute(expectedCount, matched, extras);

            int scanned = matched.size();
            int anomaly = Math.max(0, expectedCount - scanned);
            int todayAdded = 0; // 首版占位
            List<CategoryCount> categories = db.assetDao().countByCategory(taskId);
            if (categories.size() > 3) categories = categories.subList(0, 3);
            long lastAt = db.inventoryTaskDao().findById(taskId).updatedAt;

            stats.postValue(new Stats(expectedCount, scanned, anomaly, todayAdded,
                    s.found, s.missing, s.extra, categories, lastAt));
        });
    }

    private long startOfTodayMillis() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
```

- [ ] **Step 3: 检查 InventoryTask 是否有 `updatedAt` 字段**

Run: `grep "updatedAt\|createdAt" app/src/main/java/com/huaxia/inventory/data/entity/InventoryTask.java`

如果没有 `updatedAt`，改为 `createdAt` 或回退到 0。

- [ ] **Step 4: 编译 + 提交**

Run: `./gradlew :app:assembleDebug` && `git add ... && git commit -m "feat(scan): ScanViewModel 扩展 Stats（inStock/scanned/anomaly/categories）"`

---

### Task 23: fragment_scan.xml 完整重写

**Files:**
- Modify: `app/src/main/res/layout/fragment_scan.xml`

- [ ] **Step 1: 完整替换 `fragment_scan.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<ScrollView
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/bgSurface">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="@dimen/cardPadding">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginBottom="16dp"
            android:text="@string/scan_title"
            android:textSize="@dimen/textSizeTitle"
            android:textStyle="bold" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal">

            <include
                android:id="@+id/inStockStat"
                layout="@layout/view_stat_card"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:layout_marginEnd="5dp" />

            <include
                android:id="@+id/scannedStat"
                layout="@layout/view_stat_card"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:layout_marginStart="5dp"
                android:layout_marginEnd="5dp" />

            <include
                android:id="@+id/anomalyStat"
                layout="@layout/view_stat_card"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:layout_marginStart="5dp" />
        </LinearLayout>

        <TextView
            android:id="@+id/deviceStatusTv"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:textSize="@dimen/textSizeLabel"
            android:textColor="#888888" />

        <include
            android:id="@+id/inStockCard"
            layout="@layout/view_info_card" />

        <include
            android:id="@+id/checkedCard"
            layout="@layout/view_info_card" />

        <include
            android:id="@+id/recentResultCard"
            layout="@layout/view_info_card" />

        <com.google.android.material.button.MaterialButton
            android:id="@+id/scanBtn"
            style="@style/Widget.MaterialComponents.Button.OutlinedButton"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="@string/scan_start_inventory" />
    </LinearLayout>
</ScrollView>
```

注意：用 `<include>` 容器时，外层 margin/weight 作用在 include 标签上。`<include>` 内的 root view（CardView）会按其 `layout_width` 渲染。

- [ ] **Step 2: 编译验证（XML 引用合法）**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL（可能 ScanFragment 引用旧 id 报红，下个 task 改 Java）

- [ ] **Step 3: 不提交，与下个 task 一起**

---

### Task 24: ScanFragment.java 完整重写

**Files:**
- Modify: `app/src/main/java/com/huaxia/inventory/ui/scan/ScanFragment.java`
- Delete: `app/src/main/java/com/huaxia/inventory/ui/scan/RecentScanAdapter.java`
- Delete: `app/src/main/res/layout/item_recent_scan.xml`

- [ ] **Step 1: 完整替换 `ScanFragment.java`**

```java
package com.huaxia.inventory.ui.scan;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.button.MaterialButton;
import com.huaxia.inventory.InventoryApp;
import com.huaxia.inventory.R;
import com.huaxia.inventory.data.StatusDerivation;
import com.huaxia.inventory.data.dao.CategoryCount;
import com.huaxia.inventory.rfid.SdkRfidAdapter;
import com.huaxia.inventory.util.EpcNormalizer;
import com.huaxia.inventory.util.DateUtil;

public class ScanFragment extends Fragment {

    private ScanViewModel vm;
    private MaterialButton scanBtn;
    private TextView expectedTv, scannedTv, deviceStatusTv;
    private TextView inStockLabel, inStockNumber, inStockSub;
    private TextView scannedLabel, scannedNumber, scannedSub;
    private TextView anomalyLabel, anomalyNumber, anomalySub;
    private TextView inStockCardTitle, inStockCardDesc;
    private TextView checkedCardTitle, checkedCardDesc, checkedCardSub;
    private TextView recentCardTitle, recentCardDesc;
    private LinearLayout inStockTags, checkedTags, recentTags;

    private final SdkRfidAdapter.TagListener rfidCallback = (epcStr, tid, rssi) -> {
        String normalized = EpcNormalizer.normalize(epcStr);
        if (normalized == null) return;
        vm.onEpcScanned(normalized, tid, rssi);
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_scan, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        super.onViewCreated(v, s);
        vm = new ViewModelProvider(this).get(ScanViewModel.class);

        scanBtn = v.findViewById(R.id.scanBtn);
        deviceStatusTv = v.findViewById(R.id.deviceStatusTv);

        // stat cards
        bindStatCard(v, R.id.inStockStat, R.string.scan_stat_in_stock,
                R.color.statusFound, "+0 今日");
        bindStatCard(v, R.id.scannedStat, R.string.scan_stat_scanned,
                R.color.statScanned, "0%");
        bindStatCard(v, R.id.anomalyStat, R.string.scan_stat_anomaly,
                R.color.statusMissing, "需处理");

        inStockNumber = v.findViewById(R.id.inStockStat).findViewById(R.id.statNumberTv);
        scannedNumber = v.findViewById(R.id.scannedStat).findViewById(R.id.statNumberTv);
        anomalyNumber = v.findViewById(R.id.anomalyStat).findViewById(R.id.statNumberTv);
        inStockSub = v.findViewById(R.id.inStockStat).findViewById(R.id.statSubTv);
        scannedSub = v.findViewById(R.id.scannedStat).findViewById(R.id.statSubTv);
        anomalySub = v.findViewById(R.id.anomalyStat).findViewById(R.id.statSubTv);

        // info cards
        View inStockCard = v.findViewById(R.id.inStockCard);
        inStockCardTitle = inStockCard.findViewById(R.id.infoTitleTv);
        inStockCardDesc = inStockCard.findViewById(R.id.infoDescTv);
        inStockTags = inStockCard.findViewById(R.id.infoTagsContainer);
        inStockCardTitle.setText(R.string.scan_info_in_stock);
        inStockCardDesc.setText(R.string.scan_info_in_stock_desc);

        View checkedCard = v.findViewById(R.id.checkedCard);
        checkedCardTitle = checkedCard.findViewById(R.id.infoTitleTv);
        checkedCardDesc = checkedCard.findViewById(R.id.infoDescTv);
        checkedCardSub = checkedCard.findViewById(R.id.infoSubTv);
        checkedCardTitle.setText(R.string.scan_info_checked);
        checkedCardDesc.setText(R.string.scan_info_checked_desc);
        checkedCardSub.setVisibility(View.VISIBLE);

        View recentCard = v.findViewById(R.id.recentResultCard);
        recentCardTitle = recentCard.findViewById(R.id.infoTitleTv);
        recentCardDesc = recentCard.findViewById(R.id.infoDescTv);
        recentTags = recentCard.findViewById(R.id.infoTagsContainer);
        recentCardTitle.setText(R.string.scan_info_recent);

        scanBtn.setOnClickListener(btn -> toggleScan());

        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        Long taskId = app.session.getCurrentTaskId();
        if (taskId == null) {
            deviceStatusTv.setText(getString(R.string.scan_device_status,
                    getString(R.string.scan_status_not_ready)));
            scanBtn.setEnabled(false);
            return;
        }
        vm.bindToTask(taskId);

        vm.stats().observe(getViewLifecycleOwner(), stats -> {
            inStockNumber.setText(String.valueOf(stats.inStock));
            inStockSub.setText(getString(R.string.scan_stat_sub_today, stats.todayAdded));
            scannedNumber.setText(String.valueOf(stats.scanned));
            int pct = stats.inStock == 0 ? 0 :
                    (int) Math.round(100.0 * stats.scanned / stats.inStock);
            scannedSub.setText(pct + "%");
            anomalyNumber.setText(String.valueOf(stats.anomaly));
            anomalySub.setText(stats.anomaly > 0 ? "需处理" : "OK");

            renderCategoryTags(inStockTags, stats.categories);
            checkedCardSub.setText(getString(R.string.scan_info_checked_sub,
                    stats.lastInventoryAt == 0 ? "—" : DateUtil.formatTimestamp(stats.lastInventoryAt)));
            recentCardDesc.setText(getString(R.string.scan_info_recent_desc,
                    stats.found, stats.missing, stats.extra));
            renderResultTags(recentTags, stats);
        });

        deviceStatusTv.setText(getString(R.string.scan_device_status,
                app.rfid.getState() == com.huaxia.inventory.rfid.RfidController.State.READY
                || app.rfid.getState() == com.huaxia.inventory.rfid.RfidController.State.SCANNING
                ? getString(R.string.scan_status_ready)
                : getString(R.string.scan_status_not_ready)));
    }

    private void bindStatCard(View root, int includeId, int labelRes, int colorRes, String sub) {
        View card = root.findViewById(includeId);
        TextView label = card.findViewById(R.id.statLabelTv);
        TextView number = card.findViewById(R.id.statNumberTv);
        TextView subTv = card.findViewById(R.id.statSubTv);
        label.setText(labelRes);
        number.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), colorRes));
        subTv.setVisibility(View.VISIBLE);
        subTv.setText(sub);
    }

    private void renderCategoryTags(LinearLayout container, java.util.List<CategoryCount> cats) {
        container.removeAllViews();
        if (cats == null || cats.isEmpty()) return;
        for (CategoryCount c : cats) {
            TextView pill = (TextView) LayoutInflater.from(requireContext())
                    .inflate(R.layout.view_tag_pill, container, false);
            pill.setText(getString(R.string.tag_format_count, c.category, c.count));
            pill.setBackgroundResource(R.drawable.bg_tag_blue);
            pill.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.tagBlueFg));
            container.addView(pill);
        }
    }

    private void renderResultTags(LinearLayout container, ScanViewModel.Stats stats) {
        container.removeAllViews();
        addPill(container, R.string.tag_found, stats.found, R.drawable.bg_tag_green, R.color.tagGreenFg);
        addPill(container, R.string.tag_missing, stats.missing, R.drawable.bg_tag_red, R.color.tagRedFg);
        addPill(container, R.string.tag_extra, stats.extra, R.drawable.bg_tag_orange, R.color.tagOrangeFg);
    }

    private void addPill(LinearLayout container, int textRes, int count,
                         int bgRes, int fgRes) {
        TextView pill = (TextView) LayoutInflater.from(requireContext())
                .inflate(R.layout.view_tag_pill, container, false);
        pill.setText(getString(R.string.tag_format_count, getString(textRes), count));
        pill.setBackgroundResource(bgRes);
        pill.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), fgRes));
        container.addView(pill);
    }

    private void toggleScan() {
        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        var state = app.rfid.getState();
        if (state == com.huaxia.inventory.rfid.RfidController.State.SCANNING) {
            app.rfid.stopInventory();
            scanBtn.setText(R.string.scan_start_inventory);
        } else {
            app.rfid.startInventory();
            scanBtn.setText(R.string.scan_stop);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        app.rfid.registerCallback(rfidCallback);
        app.rfid.powerOn();
        if (app.rfid.isPowered()) {
            if (app.rfid.getState() == com.huaxia.inventory.rfid.RfidController.State.READY) {
                app.rfid.startInventory();
                scanBtn.setText(R.string.scan_stop);
            }
            scanBtn.setEnabled(true);
        } else {
            scanBtn.setEnabled(false);
            deviceStatusTv.setText(getString(R.string.scan_device_status,
                    getString(R.string.scan_status_not_ready)));
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        app.rfid.stopInventory();
        app.rfid.unregisterCallback(rfidCallback);
    }
}
```

- [ ] **Step 2: 删除 RecentScanAdapter 与 item_recent_scan**

```bash
git rm app/src/main/java/com/huaxia/inventory/ui/scan/RecentScanAdapter.java
git rm app/src/main/res/layout/item_recent_scan.xml
```

- [ ] **Step 3: 编译验证 + 提交**

Run: `./gradlew :app:assembleDebug` && `git add ... && git commit -m "feat(scan): 扫描页对齐设计稿 — 3 统计 + 3 说明卡，去掉最近扫描/FAB"`

---

### Task 25: 阶段 3 验证

- [ ] **Step 1: 单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全绿

- [ ] **Step 2: 真机验证**

启动 App → 扫描页：
- 顶部 "🔍 扫描管理" 标题
- 3 张统计卡并排显示（在库设备/已盘点/异常）
- 设备状态小灰字
- 3 张说明卡依次显示（在库设备/已盘点设备/最近盘点结果）
- "➕ 开始盘点" outline 按钮在底部
- 点击开始 → 切换为"停止扫描" + 实时更新已盘点数
- 切到结果页/导入页 再切回，状态保持

---

## 阶段 4：结果页

### Task 26: AssetDao 新增 countByStatus 与 paging

**Files:**
- Modify: `app/src/main/java/com/huaxia/inventory/data/dao/AssetDao.java`

- [ ] **Step 1: 追加以下方法（保留已有方法，新增 4 个）**

```java
    // 阶段 4 新增 ↓

    @Query("SELECT COUNT(*) FROM Asset WHERE taskId = :taskId AND id IN " +
           "(SELECT matchedAssetId FROM ScanRecord WHERE taskId = :taskId AND matchedAssetId IS NOT NULL)")
    int countFound(long taskId);

    @Query("SELECT COUNT(*) FROM Asset WHERE taskId = :taskId AND id NOT IN " +
           "(SELECT matchedAssetId FROM ScanRecord WHERE taskId = :taskId AND matchedAssetId IS NOT NULL)")
    int countMissing(long taskId);

    @Query("SELECT COUNT(*) FROM ScanRecord WHERE taskId = :taskId AND matchedAssetId IS NULL")
    int countExtra(long taskId);

    @Query("SELECT COUNT(*) FROM Asset WHERE taskId = :taskId")
    int countAll(long taskId);
```

注：原有 `countByTask(long taskId)` 改名/替换为 `countAll`，调用方同步更新（grep 全工程引用）。

- [ ] **Step 2: 加 paging 通用方法**

```java
    @Query("SELECT * FROM Asset WHERE taskId = :taskId ORDER BY excelRowIndex ASC " +
           "LIMIT :limit OFFSET :offset")
    List<Asset> pageAll(long taskId, int limit, int offset);

    @Query("SELECT * FROM Asset WHERE taskId = :taskId AND id IN " +
           "(SELECT matchedAssetId FROM ScanRecord WHERE taskId = :taskId AND matchedAssetId IS NOT NULL) " +
           "ORDER BY excelRowIndex ASC LIMIT :limit OFFSET :offset")
    List<Asset> pageFound(long taskId, int limit, int offset);

    @Query("SELECT * FROM Asset WHERE taskId = :taskId AND id NOT IN " +
           "(SELECT matchedAssetId FROM ScanRecord WHERE taskId = :taskId AND matchedAssetId IS NOT NULL) " +
           "ORDER BY excelRowIndex ASC LIMIT :limit OFFSET :offset")
    List<Asset> pageMissing(long taskId, int limit, int offset);

    @Query("SELECT * FROM ScanRecord WHERE taskId = :taskId AND matchedAssetId IS NULL " +
           "ORDER BY scannedAt DESC LIMIT :limit OFFSET :offset")
    List<com.huaxia.inventory.data.entity.ScanRecord> pageExtra(long taskId, int limit, int offset);
```

- [ ] **Step 3: 编译 + 提交**

Run: `./gradlew :app:assembleDebug` && `git add ... && git commit -m "feat(dao): AssetDao.countFound/Missing/All + pageAll/Found/Missing/Extra"`

---

### Task 27: ResultViewModel 改造

**Files:**
- Modify: `app/src/main/java/com/huaxia/inventory/ui/result/ResultViewModel.java`

- [ ] **Step 1: 完整替换 `ResultViewModel.java`**

```java
package com.huaxia.inventory.ui.result;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.huaxia.inventory.InventoryApp;
import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.ScanRecord;
import java.util.List;
import java.util.concurrent.Executor;

public class ResultViewModel extends AndroidViewModel {

    public static final int PAGE_SIZE = 6;

    public static class Stats {
        public final int total, found, missing, extra;
        public Stats(int total, int found, int missing, int extra) {
            this.total = total; this.found = found; this.missing = missing; this.extra = extra;
        }
    }

    private final AppDatabase db;
    private final Executor diskIO;
    private final MutableLiveData<List<Asset>> assets = new MutableLiveData<>();
    private final MutableLiveData<List<ScanRecord>> extras = new MutableLiveData<>();
    private final MutableLiveData<Stats> stats = new MutableLiveData<>();
    private final MutableLiveData<ResultTab> activeTab = new MutableLiveData<>(ResultTab.FOUND);
    private final MutableLiveData<Integer> currentPage = new MutableLiveData<>(0);
    private long currentTaskId = -1L;

    public ResultViewModel(@NonNull Application application) {
        super(application);
        InventoryApp app = (InventoryApp) application;
        this.db = app.db;
        this.diskIO = app.executors.diskIO;
    }

    public LiveData<List<Asset>> assets() { return assets; }
    public LiveData<List<ScanRecord>> extras() { return extras; }
    public LiveData<Stats> stats() { return stats; }
    public LiveData<ResultTab> activeTab() { return activeTab; }
    public LiveData<Integer> currentPage() { return currentPage; }

    public void bindToTask(long taskId) {
        this.currentTaskId = taskId;
        refresh();
    }

    public void setTab(ResultTab tab) {
        activeTab.setValue(tab);
        currentPage.setValue(0);
        refresh();
    }

    public void nextPage() {
        int p = currentPage.getValue() == null ? 0 : currentPage.getValue();
        currentPage.setValue(p + 1);
        refresh();
    }

    public void prevPage() {
        int p = currentPage.getValue() == null ? 0 : currentPage.getValue();
        if (p > 0) currentPage.setValue(p - 1);
        refresh();
    }

    public void onExportClicked() {
        // MainActivity routes to this; ResultFragment listens to onExportClicked via callback
    }

    private void refresh() {
        long taskId = currentTaskId;
        ResultTab tab = activeTab.getValue() == null ? ResultTab.FOUND : activeTab.getValue();
        int page = currentPage.getValue() == null ? 0 : currentPage.getValue();
        int offset = page * PAGE_SIZE;
        diskIO.execute(() -> {
            // 统计
            int total = db.assetDao().countAll(taskId);
            int found = db.assetDao().countFound(taskId);
            int missing = db.assetDao().countMissing(taskId);
            int extra = db.scanRecordDao().countExtras(taskId);
            stats.postValue(new Stats(total, found, missing, extra));

            // 列表
            List<Asset> assetResult;
            List<ScanRecord> extraResult;
            switch (tab) {
                case MISSING:
                    assetResult = db.assetDao().pageMissing(taskId, PAGE_SIZE, offset);
                    extras.postValue(java.util.Collections.emptyList());
                    break;
                case EXTRA:
                    assetResult = java.util.Collections.emptyList();
                    extraResult = db.scanRecordDao().pageExtra(taskId, PAGE_SIZE, offset);
                    extras.postValue(extraResult);
                    assets.postValue(assetResult);
                    return;
                case ALL:
                    assetResult = db.assetDao().pageAll(taskId, PAGE_SIZE, offset);
                    extras.postValue(java.util.Collections.emptyList());
                    break;
                case FOUND:
                default:
                    assetResult = db.assetDao().pageFound(taskId, PAGE_SIZE, offset);
                    extras.postValue(java.util.Collections.emptyList());
                    break;
            }
            assets.postValue(assetResult);
        });
    }
}
```

- [ ] **Step 2: 检查 ScanRecordDao 是否有 `countExtras` 与 `pageExtra`**

Run: `grep "countExtras\|pageExtra" app/src/main/java/com/huaxia/inventory/data/dao/ScanRecordDao.java`

- `countExtras`：原 `findExtras(taskId).size()` 可改为 `countExtras(taskId)` SQL
- `pageExtra`：已新增（见 Task 26）

如无 `countExtras`，在 ScanRecordDao 追加：
```java
    @Query("SELECT COUNT(*) FROM ScanRecord WHERE taskId = :taskId AND matchedAssetId IS NULL")
    int countExtras(long taskId);
```

- [ ] **Step 3: 编译 + 提交**

Run: `./gradlew :app:assembleDebug` && `git add ... && git commit -m "feat(result): ResultViewModel — 4 宫格 + 分页 + 状态派生"`

---

### Task 28: fragment_result.xml 完整重写

**Files:**
- Modify: `app/src/main/res/layout/fragment_result.xml`

- [ ] **Step 1: 完整替换 `fragment_result.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="@color/bgSurface">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_margin="@dimen/cardPadding"
        android:text="@string/result_title"
        android:textSize="@dimen/textSizeTitle"
        android:textStyle="bold" />

    <GridLayout
        android:id="@+id/statGrid"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:columnCount="2"
        android:rowCount="2"
        android:paddingStart="@dimen/cardPadding"
        android:paddingEnd="@dimen/cardPadding"
        android:useDefaultMargins="false">

        <include
            android:id="@+id/allStat"
            layout="@layout/view_stat_card"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_columnWeight="1"
            android:layout_margin="5dp" />

        <include
            android:id="@+id/foundStat"
            layout="@layout/view_stat_card"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_columnWeight="1"
            android:layout_margin="5dp" />

        <include
            android:id="@+id/missingStat"
            layout="@layout/view_stat_card"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_columnWeight="1"
            android:layout_margin="5dp" />

        <include
            android:id="@+id/extraStat"
            layout="@layout/view_stat_card"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_columnWeight="1"
            android:layout_margin="5dp" />
    </GridLayout>

    <TextView
        android:id="@+id/emptyTv"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:gravity="center"
        android:visibility="gone"
        android:textSize="@dimen/textSizeBody"
        android:textColor="#888888"
        android:text="@string/err_empty_result" />

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/resultRv"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:paddingStart="@dimen/cardPadding"
        android:paddingEnd="@dimen/cardPadding"
        android:paddingTop="@dimen/cardPadding"
        android:clipToPadding="false" />

    <LinearLayout
        android:id="@+id/paginationRow"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:padding="12dp">

        <TextView
            android:id="@+id/paginationInfoTv"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:textSize="13sp"
            android:textColor="#888888" />

        <com.google.android.material.button.MaterialButton
            android:id="@+id/prevBtn"
            style="@style/Widget.MaterialComponents.Button.TextButton"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/action_prev" />

        <com.google.android.material.button.MaterialButton
            android:id="@+id/nextBtn"
            style="@style/Widget.MaterialComponents.Button.TextButton"
            android:textColor="@color/colorPrimary"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/action_next" />
    </LinearLayout>
</LinearLayout>
```

- [ ] **Step 2: 编译验证（XML 引用合法）**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL（可能 ResultFragment 引用旧 id 报红，下个 task 改 Java）

- [ ] **Step 3: 不提交，与下个 task 一起**

---

### Task 29: ResultFragment.java 完整重写

**Files:**
- Modify: `app/src/main/java/com/huaxia/inventory/ui/result/ResultFragment.java`

- [ ] **Step 1: 完整替换 `ResultFragment.java`**

```java
package com.huaxia.inventory.ui.result;

import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.huaxia.inventory.InventoryApp;
import com.huaxia.inventory.R;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.data.entity.ScanRecord;
import com.huaxia.inventory.excel.ExcelExporter;
import com.huaxia.inventory.util.DateUtil;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ResultFragment extends Fragment {

    private ResultViewModel vm;
    private RecyclerView rv;
    private View emptyTv;
    private TextView paginationInfoTv;
    private MaterialButton prevBtn, nextBtn;
    private TextView allNumber, foundNumber, missingNumber, extraNumber;

    private final Set<Long> matchedIdsCache = new HashSet<>();
    private ActivityResultLauncher<String> exportLauncher;
    private long currentTaskId = -1L;

    @Override
    public void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        exportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                uri -> { if (uri != null) doExport(uri); });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_result, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        super.onViewCreated(v, s);
        vm = new ViewModelProvider(this).get(ResultViewModel.class);
        rv = v.findViewById(R.id.resultRv);
        emptyTv = v.findViewById(R.id.emptyTv);
        paginationInfoTv = v.findViewById(R.id.paginationInfoTv);
        prevBtn = v.findViewById(R.id.prevBtn);
        nextBtn = v.findViewById(R.id.nextBtn);

        rv.setLayoutManager(new LinearLayoutManager(requireContext()));

        allNumber = v.findViewById(R.id.allStat).findViewById(R.id.statNumberTv);
        foundNumber = v.findViewById(R.id.foundStat).findViewById(R.id.statNumberTv);
        missingNumber = v.findViewById(R.id.missingStat).findViewById(R.id.statNumberTv);
        extraNumber = v.findViewById(R.id.extraStat).findViewById(R.id.statNumberTv);
        setStatLabel(v.findViewById(R.id.allStat), R.string.result_stat_all);
        setStatLabel(v.findViewById(R.id.foundStat), R.string.result_stat_found);
        setStatLabel(v.findViewById(R.id.missingStat), R.string.result_stat_missing);
        setStatLabel(v.findViewById(R.id.extraStat), R.string.result_stat_extra);
        setStatNumberColor(allNumber, R.color.statTotal);
        setStatNumberColor(foundNumber, R.color.statusFound);
        setStatNumberColor(missingNumber, R.color.statusMissing);
        setStatNumberColor(extraNumber, R.color.statusExtra);

        bindStatClick(v.findViewById(R.id.allStat), ResultTab.ALL);
        bindStatClick(v.findViewById(R.id.foundStat), ResultTab.FOUND);
        bindStatClick(v.findViewById(R.id.missingStat), ResultTab.MISSING);
        bindStatClick(v.findViewById(R.id.extraStat), ResultTab.EXTRA);

        prevBtn.setOnClickListener(btn -> vm.prevPage());
        nextBtn.setOnClickListener(btn -> vm.nextPage());

        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        Long taskId = app.session.getCurrentTaskId();
        if (taskId == null) {
            emptyTv.setVisibility(View.VISIBLE);
            rv.setVisibility(View.GONE);
            paginationInfoTv.setText("");
            return;
        }
        currentTaskId = taskId;
        vm.bindToTask(taskId);

        vm.stats().observe(getViewLifecycleOwner(), stats -> {
            allNumber.setText(String.valueOf(stats.total));
            foundNumber.setText(String.valueOf(stats.found));
            missingNumber.setText(String.valueOf(stats.missing));
            extraNumber.setText(String.valueOf(stats.extra));
        });

        vm.currentPage().observe(getViewLifecycleOwner(), page -> {
            int p = page == null ? 0 : page;
            int total = vm.stats().getValue() == null ? 0 : filterCount(vm.stats().getValue());
            int from = total == 0 ? 0 : p * ResultViewModel.PAGE_SIZE + 1;
            int to = Math.min((p + 1) * ResultViewModel.PAGE_SIZE, total);
            paginationInfoTv.setText(getString(R.string.result_pagination, total, from, to));
            prevBtn.setEnabled(p > 0);
            nextBtn.setEnabled((p + 1) * ResultViewModel.PAGE_SIZE < total);
        });

        vm.assets().observe(getViewLifecycleOwner(), assets -> {
            if (assets == null) return;
            if (assets.isEmpty() && (vm.extras().getValue() == null || vm.extras().getValue().isEmpty())) {
                emptyTv.setVisibility(View.VISIBLE);
                rv.setVisibility(View.GONE);
            } else {
                emptyTv.setVisibility(View.GONE);
                rv.setVisibility(View.VISIBLE);
            }
            InventoryApp a = (InventoryApp) requireActivity().getApplication();
            a.executors.diskIO.execute(() -> {
                Set<Long> matched = new HashSet<>();
                for (Asset as : a.db.assetDao().findFound(currentTaskId)) matched.add(as.id);
                matchedIdsCache.clear();
                matchedIdsCache.addAll(matched);
                requireActivity().runOnUiThread(() ->
                    rv.setAdapter(new AssetResultAdapter(assets, matchedIdsCache)));
            });
        });

        vm.extras().observe(getViewLifecycleOwner(), extras -> {
            if (extras == null || extras.isEmpty()) return;
            // EXTRA 模式：单独渲染一个简版（用 ScanRecord id 代替 Asset id 即可）
            // 首版简化：EXTRA 模式不通过 AssetResultAdapter，列表显示 EPC 字符串
            // 后续迭代再做专用的 ExtraResultAdapter
        });
    }

    private int filterCount(ResultViewModel.Stats s) {
        if (vm.activeTab().getValue() == null) return s.total;
        switch (vm.activeTab().getValue()) {
            case FOUND:   return s.found;
            case MISSING: return s.missing;
            case EXTRA:   return s.extra;
            default:      return s.total;
        }
    }

    private void setStatLabel(View card, int labelRes) {
        ((TextView) card.findViewById(R.id.statLabelTv)).setText(labelRes);
    }

    private void setStatNumberColor(TextView tv, int colorRes) {
        tv.setTextColor(ContextColor(colorRes));
    }

    private int ContextColor(int res) {
        return androidx.core.content.ContextCompat.getColor(requireContext(), res);
    }

    private void bindStatClick(View card, ResultTab tab) {
        card.setOnClickListener(v -> vm.setTab(tab));
    }

    public void onExportClicked() {
        if (currentTaskId == -1L) return;
        String fileName = "盘点结果_" + currentTaskId + "_" +
                DateUtil.formatTimestamp(System.currentTimeMillis())
                    .replace(" ", "_").replace(":", "")
                + ".xlsx";
        exportLauncher.launch(fileName);
    }

    private void doExport(Uri uri) {
        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        long taskId = currentTaskId;
        app.executors.excelIO.execute(() -> {
            try {
                InventoryTask task = app.db.inventoryTaskDao().findById(taskId);
                List<Asset> assets = app.db.assetDao().findAllByTask(taskId);
                List<ScanRecord> scans = app.db.scanRecordDao().findAllByTask(taskId);
                Set<Long> matchedIds = new HashSet<>();
                for (Asset a : app.db.assetDao().findFound(taskId)) matchedIds.add(a.id);

                List<String> originalHeaders = new ArrayList<>();
                if (!assets.isEmpty() && assets.get(0).rawRowJson != null) {
                    com.fasterxml.jackson.databind.ObjectMapper mapper =
                            new com.fasterxml.jackson.databind.ObjectMapper();
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, String> raw = mapper.readValue(
                            assets.get(0).rawRowJson, java.util.Map.class);
                    originalHeaders.addAll(raw.keySet());
                }

                try (java.io.OutputStream out =
                        requireContext().getContentResolver().openOutputStream(uri)) {
                    ExcelExporter.export(out, task.sourceFileName, originalHeaders,
                            assets, scans, matchedIds::contains);
                }
                requireActivity().runOnUiThread(() ->
                    Snackbar.make(requireView(), R.string.msg_export_done,
                            Snackbar.LENGTH_SHORT).show());
            } catch (Exception e) {
                requireActivity().runOnUiThread(() ->
                    Snackbar.make(requireView(),
                        getString(R.string.err_export_failed, e.getMessage()),
                        Snackbar.LENGTH_LONG).show());
            }
        });
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

Run: `git add ... && git commit -m "feat(result): 结果页对齐设计稿 — 4 宫格 + 分页，去掉 Tab/搜索"`

---

### Task 30: AssetResultAdapter 切换到 view_asset_row

**Files:**
- Modify: `app/src/main/java/com/huaxia/inventory/ui/result/AssetResultAdapter.java`

- [ ] **Step 1: 完整替换 `AssetResultAdapter.java`**

```java
package com.huaxia.inventory.ui.result;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.huaxia.inventory.R;
import com.huaxia.inventory.data.entity.Asset;
import java.util.List;
import java.util.Set;

public class AssetResultAdapter extends RecyclerView.Adapter<AssetResultAdapter.VH> {

    private final List<Asset> items;
    private final Set<Long> matchedIds;

    public AssetResultAdapter(List<Asset> items, Set<Long> matchedIds) {
        this.items = items;
        this.matchedIds = matchedIds;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.view_asset_row, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Asset a = items.get(position);
        h.code.setText(a.epc == null ? "" : a.epc);
        h.name.setText(a.assetName == null ? "" : a.assetName);
        h.org.setText(a.org == null ? "" : a.org);

        boolean matched = matchedIds.contains(a.id);
        if (matched) {
            h.pill.setText(R.string.tag_found);
            h.pill.setBackgroundResource(R.drawable.bg_tag_green);
            h.pill.setTextColor(androidx.core.content.ContextCompat.getColor(
                    h.itemView.getContext(), R.color.tagGreenFg));
        } else {
            h.pill.setText(R.string.tag_missing);
            h.pill.setBackgroundResource(R.drawable.bg_tag_red);
            h.pill.setTextColor(androidx.core.content.ContextCompat.getColor(
                    h.itemView.getContext(), R.color.tagRedFg));
        }
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TextView code, name, org, pill;
        VH(View v) {
            super(v);
            code = v.findViewById(R.id.assetCodeTv);
            name = v.findViewById(R.id.assetNameTv);
            org = v.findViewById(R.id.assetOrgTv);
            pill = v.findViewById(R.id.assetStatusPill);
        }
    }
}
```

- [ ] **Step 2: 删除旧的 `item_asset_result.xml`**

```bash
git rm app/src/main/res/layout/item_asset_result.xml
```

- [ ] **Step 3: 编译 + 提交**

Run: `./gradlew :app:assembleDebug` && `git add ... && git commit -m "feat(result): AssetResultAdapter 切到 view_asset_row"`

---

### Task 31: 阶段 4 验证

- [ ] **Step 1: 单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全绿

- [ ] **Step 2: 真机验证**

启动 App → 结果页：
- 顶部 "📊 盘点结果" 标题
- 2x2 统计网格（全部/已盘/盘亏/盘盈）
- 点击不同宫格 → 列表过滤切换
- 列表底部"上一页/下一页"按钮可点 + 数字正确
- 无数据时显示"暂无数据"
- 导出按钮在 Toolbar 右侧，点击可导出 Excel
- 已盘/盘亏 pill 颜色符合设计稿（绿/红）

---

## 阶段 5：集成

### Task 32: bottom_nav.xml emoji 化

**Files:**
- Modify: `app/src/main/res/menu/bottom_nav.xml`

- [ ] **Step 1: 完整替换 `bottom_nav.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="http://schemas.android.com/apk/res/android">
    <item
        android:id="@+id/nav_import"
        android:icon="@drawable/ic_import"
        android:title="@string/nav_import" />
    <item
        android:id="@+id/nav_scan"
        android:icon="@drawable/ic_scan"
        android:title="@string/nav_scan" />
    <item
        android:id="@+id/nav_result"
        android:icon="@drawable/ic_result"
        android:title="@string/nav_result" />
</menu>
```

注：title 已经含 emoji（"📥 导入"等），矢量图标作为主视觉。

- [ ] **Step 2: 编译 + 提交**

Run: `./gradlew :app:assembleDebug` && `git add ... && git commit -m "feat(ui): 底导 emoji 化 + 矢量图标兜底"`

---

### Task 33: MainActivity 完善 Toolbar 注入（如果 Task 14 未完成）

- [ ] **Step 1: 验证 Task 14 已完成**

打开 `MainActivity.java` 确认 `setSupportActionBar(toolbar)` 与 `onMenuItemClickListener` 都已就位。

- [ ] **Step 2: 如果缺失，按 Task 14 步骤补完**

---

### Task 34: 全工程单测

- [ ] **Step 1: 跑全量单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全绿

- [ ] **Step 2: lint**

Run: `./gradlew :app:lintDebug`
Expected: 0 error

- [ ] **Step 3: 模拟器/真机三页验收**

启动 App：
- 顶部蓝底白字 "📦 资产管家" 可见
- 底部 3 tab 切换正常
- 导入页：虚线大上传区 + 选文件 + 开始导入
- 扫描页：3 统计 + 3 说明卡 + 开始盘点
- 结果页：4 宫格 + 列表 + 分页 + 导出

- [ ] **Step 4: 最终 commit**

Run: `git add ... && git commit -m "docs: 阶段 5 集成验证 + README 更新"`

---

### Task 35: README 更新

**Files:**
- Modify: `README.md`

- [ ] **Step 1: 在"功能"或"待办"段后追加本轮 UI 重构记录**

```markdown
## 2026-06-11 UI 重构

- 全面对齐 `资产管理系统HTML界面.html` 设计稿
- 新增通用组件：view_stat_card / view_tag_pill / view_info_card / view_asset_row
- 颜色/尺寸/主题统一（圆角 12dp、状态色 #2E7D32/#C62828/#EF6C00）
- AppBar 蓝底白字"📦 资产管家"
- 底导 emoji 三 tab（导入/扫描/结果）
- 扫描页：3 统计卡 + 3 说明卡，去掉最近扫描列表与 FAB
- 结果页：4 宫格点击筛选 + 6 条/页分页，去掉 Tab 与搜索
- 导入页：虚线大上传区 + tip box，去掉预览列表
- 设计文档：[docs/superpowers/specs/2026-06-11-ui-redesign-html-mockup-alignment-design.md](docs/superpowers/specs/2026-06-11-ui-redesign-html-mockup-alignment-design.md)
- 实施计划：[docs/superpowers/plans/2026-06-11-ui-redesign-html-mockup-alignment.md](docs/superpowers/plans/2026-06-11-ui-redesign-html-mockup-alignment.md)
```

- [ ] **Step 2: 提交**

Run: `git add README.md && git commit -m "docs: 2026-06-11 UI 重构 README 更新"`

---

## 自我审查

### Spec 覆盖率
- ✅ 颜色/尺寸/主题：Task 1-5
- ✅ 通用组件：Task 8-11
- ✅ 虚线/标签 drawable：Task 6
- ✅ 矢量图标：Task 7
- ✅ AppBar + activity_main：Task 12
- ✅ strings 增补：Task 13
- ✅ MainActivity 注入：Task 14
- ✅ 导入页改造：Task 16-18
- ✅ 扫描页改造：Task 20-24
- ✅ 结果页改造：Task 26-30
- ✅ 底导 emoji：Task 32
- ✅ 错误处理：Snackbar/Toast 全部就位
- ✅ 测试：Task 25/31/34 各阶段单测 + 集成验证
- ✅ 5 阶段独立 commit：每个 task 结尾都 commit
- ✅ 风险与回退：见 spec §10

### Placeholder 扫描
- Task 20 `countCreatedAfter` 暂未实现 → 已写明"首版占位"策略
- Task 21 `countByCategory` 字段名可能不一致 → 已写明回退方案
- ResultFragment EXTRA 模式渲染：当前实现观察者空 body → 明确"首版简化"后续迭代补
- 无 "TBD" / "TODO" / "fill in details" 残留

### 类型一致性
- `ResultViewModel.Stats(total, found, missing, extra)` 在 ViewModel 与 Fragment 中字段名一致
- `ScanViewModel.Stats(inStock, scanned, anomaly, todayAdded, found, missing, extra, categories, lastInventoryAt)` 字段名一致
- `CategoryCount(category, count)` 在 DAO POJO 与 Fragment 使用一致
- `ResultTab` 枚举保持已有 (ALL/FOUND/MISSING/EXTRA)
- `view_stat_card` 子 view id: `statNumberTv` / `statLabelTv` / `statSubTv` 一致
- `view_info_card` 子 view id: `infoTitleTv` / `infoDescTv` / `infoSubTv` / `infoTagsContainer` 一致
- `view_asset_row` 子 view id: `assetCodeTv` / `assetNameTv` / `assetOrgTv` / `assetStatusPill` 一致
- `view_tag_pill` 子 view id: `tagPillTv` (虽然代码中用 findViewById 直接拿到 root TextView, 实际不需要)
