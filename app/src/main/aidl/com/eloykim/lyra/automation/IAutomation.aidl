package com.eloykim.lyra.automation;

/**
 * Lyra 的自动化能力接口。
 *
 * 设计约定：
 * - 所有方法返回 JSON 字符串（UTF-8），失败时 ok=false 并给出 code/message；
 * - observe() 返回的 observation 带 obsId，后续动作必须回传 obsId；
 * - 动作成功后，返回体里会附带「动作执行后的新观察」，调用方不需要再 observe 一次；
 * - 编号（nodeId）只在同一次 observation 内有效。
 */
interface IAutomation {

    /** 无障碍服务是否已连接、可执行动作。 */
    boolean isReady();

    /**
     * 抓取当前屏幕，返回压缩后的观察 JSON。
     * 结构：{ok, obsId, package, capturedAt, truncated, counts, elements[]}
     */
    String observe();

    /** 点击。obsId 用于校验观察是否过期（0 表示不校验）。 */
    String tap(int nodeId, long obsId);

    /** 按 View Id（resource id）点击；支持完整 `pkg:id/name` 或只给 `name`。 */
    String tapByViewId(String viewId, long obsId);

    /** 按坐标点击（设备像素），用于无障碍树残缺的界面。 */
    String tapAt(int x, int y);

    /** 长按坐标。durationMs 建议 400–1200。 */
    String longPress(int x, int y, int durationMs);

    /** 坐标滑动。 */
    String swipe(int x1, int y1, int x2, int y2, int durationMs);

    /** 向输入框写入文本。 */
    String setText(int nodeId, long obsId, String text);

    /** 滚动。direction: 0 向下 / 1 向上 / 2 向左 / 3 向右。 */
    String scroll(int direction, long obsId);

    /** 全局动作：back / home / recents / notifications。 */
    String globalAction(String action);

    /** 返回上一级。 */
    String back();

    /** 打开最近任务。 */
    String recents();

    /** 启动应用。 */
    String launch(String packageName);

    /**
     * 截屏。返回 {ok, path, width, height}；path 为应用缓存目录下的 PNG。
     * 不截取 Lyra 自身界面（策略拒绝）。
     */
    String screenshot();

    /**
     * 列出最近的媒体文件（名称/大小/类型/时间），不含文件内容。
     * 需要 READ_EXTERNAL_STORAGE；未授权时返回 permission_required。
     */
    String listFiles(int limit);
}
