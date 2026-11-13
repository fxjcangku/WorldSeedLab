package io.github.fxjcangku.worldseedlab.client.ui;

/** 面板、地图、文字和点击区域共用投影，窗口缩放不会裁掉下方操作。 */
record PanelViewport(int width, int height, float x, float y, float scale) {
    static PanelViewport fit(int screenWidth, int screenHeight) {
        int margin = Math.min(12, Math.min(screenWidth, screenHeight) / 20);
        int availableWidth = Math.max(1, screenWidth - margin * 2);
        int availableHeight = Math.max(1, screenHeight - margin * 2);
        float scale = Math.min(availableWidth / 900f, availableHeight / 520f);
        int width = Math.min(1120, Math.max(900, (int) (availableWidth / scale)));
        int height = Math.min(660, Math.max(520, (int) (availableHeight / scale)));
        return new PanelViewport(width, height, (screenWidth - width * scale) / 2f,
                (screenHeight - height * scale) / 2f, scale);
    }

    int screenX(int x) { return Math.round(this.x + x * scale); }
    int screenY(int y) { return Math.round(this.y + y * scale); }
    int logicalX(double x) { return (int) Math.floor((x - this.x) / scale); }
    int logicalY(double y) { return (int) Math.floor((y - this.y) / scale); }
}
