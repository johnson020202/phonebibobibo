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

enum class EffectCategory(val label: String) {
    HORN("车辆喇叭"),
    SHIP_RAIL("船舶与轨道"),
    ENGINE("引擎与速度"),
    SIGNAL("提示与暗号"),
    SCENE("更多场景")
}

/**
 * One-shot scene sounds. durationMs drives both the synth envelope and the
 * controller's status timeout, so the UI never shows a stale "playing" state.
 */
enum class SoundEffect(
    val label: String,
    val hint: String,
    val durationMs: Long,
    val category: EffectCategory
) {
    TRUCK_HORN("大车气喇叭", "低沉双音", 1_700, EffectCategory.HORN),
    CAR_HORN("汽车喇叭", "双音鸣笛", 1_100, EffectCategory.HORN),
    BUS_HORN("公交车喇叭", "宽厚双音", 3_000, EffectCategory.HORN),
    MOTO_HORN("摩托车喇叭", "高音短鸣", 2_000, EffectCategory.HORN),
    VINTAGE_HORN("老式喇叭", "阿呼嘎", 2_600, EffectCategory.HORN),
    TRAIN_HORN("火车汽笛", "长鸣和弦", 4_495, EffectCategory.HORN),

    SHIP_HORN("轮船汽笛", "低沉长鸣", 2_900, EffectCategory.SHIP_RAIL),
    WARSHIP_HORN("军舰汽笛", "三短一长", 2_600, EffectCategory.SHIP_RAIL),
    CRUISE_HORN("游轮汽笛", "明亮三音", 4_000, EffectCategory.SHIP_RAIL),
    STATION_CHIME("高铁站叮咚", "报站提示", 3_900, EffectCategory.SHIP_RAIL),
    METRO_CHIME("地铁关门音", "四声提示", 2_400, EffectCategory.SHIP_RAIL),
    CROSSING_BELL("道口警铃", "交替铃响", 4_000, EffectCategory.SHIP_RAIL),

    PORSCHE_REV("保时捷轰鸣", "引擎拉转", 3_800, EffectCategory.ENGINE),
    ACCEL("跑车加速", "声浪上扬", 3_200, EffectCategory.ENGINE),
    MOTO_REV("摩托车轰鸣", "高转拉鸣", 2_500, EffectCategory.ENGINE),
    DIESEL_IDLE("柴油卡车", "低转怠速", 4_000, EffectCategory.ENGINE),
    V8_REV("V8 肌肉车", "低频咆哮", 4_000, EffectCategory.ENGINE),
    RACE_SHIFT("赛车换挡", "连续换挡", 4_000, EffectCategory.ENGINE),

    CODE_BEEP("暗号滴滴", "连续短鸣", 1_950, EffectCategory.SIGNAL),
    MORSE("摩斯电码", "SOS 呼叫", 4_000, EffectCategory.SIGNAL),
    RADAR("雷达扫描", "声呐回波", 2_100, EffectCategory.SIGNAL),
    AIRPORT_CHIME("机场登机音", "四声提示", 3_948, EffectCategory.SIGNAL),
    SCHOOL_BELL("学校铃声", "电铃长响", 3_933, EffectCategory.SIGNAL),
    BICYCLE_BELL("自行车铃", "叮叮两声", 2_500, EffectCategory.SIGNAL),

    ICE_CREAM("冰淇淋车", "音乐盒旋律", 4_000, EffectCategory.SCENE),
    REVERSING_BEEP("倒车提示", "慢速滴滴", 1_900, EffectCategory.SCENE),
    TRUCK_REVERSE("卡车倒车", "低音滴滴", 4_000, EffectCategory.SCENE),
    CONSTRUCTION_ALARM("施工警报", "急促高低音", 3_035, EffectCategory.SCENE),
    AIR_RAID("空袭警报", "长音起伏", 5_000, EffectCategory.SCENE),
    POLICE_PA("警用扩音器", "电台试音", 4_920, EffectCategory.SCENE)
}

enum class LightMode(val label: String, val hint: String) {
    AUTO("自动联动", "随动"),
    MODE_1("模式 1", "示宽"),
    MODE_2("模式 2", "双爆"),
    MODE_3("模式 3", "大循环"),
    STEADY("拦停检车", "前射灯")
}
