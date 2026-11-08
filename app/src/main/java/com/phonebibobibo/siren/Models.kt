package com.phonebibobibo.siren

enum class SirenProgram(val label: String, val hint: String) {
    WAIL_STD("巡航 Wall", "标准"),
    WAIL_SLOW("慢速 Wall", "慢速"),
    WAIL_FAST("快速 Wall", "快速"),
    YELP("急促 Yelp", "急促"),
    PURSUIT("追击变频", "追击"),
    PHASER("极速穿透", "极速"),
    TREMOLO("战术颤音", "高频颤音"),
    HI_LO("救护 Hi-Lo", "救护双音"),
    ROTATE("自动", "随机轮换"),
    DUAL_A("并发 A", "巡航 + 急促"),
    DUAL_B("并发 B", "急促 + 颤音")
}

enum class LightMode(val label: String, val hint: String) {
    AUTO("自动联动", "随动"),
    MODE_1("模式 1", "示宽"),
    MODE_2("模式 2", "双爆"),
    MODE_3("模式 3", "大循环"),
    STEADY("拦停检车", "前射灯")
}
