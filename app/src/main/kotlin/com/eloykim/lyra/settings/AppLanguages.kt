package com.eloykim.lyra.settings

/**
 * 内置语言 / 方言（区域 + 语系）。
 *
 * 显示统一为「English · 本地写法」。
 * tag 用 BCP-47；汉语方言没有注册码的，用私有扩展 `zh-Hans-CN-x-*`，
 * 保证既合法又能回落到简体中文资源。
 *
 * speechLanguage 是语音引擎实际支持的语言：粤语/闽南语挂 zh-HK/zh-TW，
 * 官话各方言片区统一 zh-CN（识别引擎没有更细的选项）。
 *
 * 只影响语音识别与界面语言，不进入模型对话。
 */
data class LanguageOption(
    val id: String,
    val english: String,
    val native: String,
    val tag: String,
    val speechLanguage: String,
    val group: String,
) {
    val label: String get() = "$english · $native"
}

private fun mandarin(id: String, english: String, native: String) = LanguageOption(
    id = "zh-Hans-CN-x-$id",
    english = english,
    native = native,
    tag = "zh-Hans-CN-x-$id",
    speechLanguage = "zh-CN",
    group = "Regional Chinese"
)

val AppLanguages: List<LanguageOption> = listOf(
    // —— Chinese ——
    LanguageOption(
        id = "zh-Hans-CN",
        english = "Chinese, Simplified (China)",
        native = "简体中文",
        tag = "zh-Hans-CN",
        speechLanguage = "zh-CN",
        group = "Chinese",
    ),
    LanguageOption(
        id = "zh-Hant-HK",
        english = "Chinese, Traditional (Hong Kong)",
        native = "繁體中文（香港）",
        tag = "zh-Hant-HK",
        speechLanguage = "zh-HK",
        group = "Chinese",
    ),
    LanguageOption(
        id = "zh-Hant-TW",
        english = "Chinese, Traditional (Taiwan)",
        native = "繁體中文（台灣）",
        tag = "zh-Hant-TW",
        speechLanguage = "zh-TW",
        group = "Chinese",
    ),
    LanguageOption(
        id = "zh-Hant-MO",
        english = "Chinese, Traditional (Macao)",
        native = "繁體中文（澳門）",
        tag = "zh-Hant-MO",
        speechLanguage = "zh-HK",
        group = "Chinese",
    ),

    // —— Cantonese ——
    LanguageOption(
        id = "yue-Hant-HK",
        english = "Cantonese (Hong Kong)",
        native = "粵語（香港）",
        tag = "yue-Hant-HK",
        speechLanguage = "zh-HK",
        group = "Cantonese"
    ),
    LanguageOption(
        id = "yue-Hans-CN",
        english = "Cantonese (Mainland China)",
        native = "粤语（中国大陆）",
        tag = "yue-Hans-CN",
        speechLanguage = "zh-HK",
        group = "Cantonese"
    ),
    LanguageOption(
        id = "yue-Hant-MO",
        english = "Cantonese (Macao)",
        native = "粵語（澳門）",
        tag = "yue-Hant-MO",
        speechLanguage = "zh-HK",
        group = "Cantonese"
    ),

    // —— Hokkien / Min ——
    LanguageOption(
        id = "nan-Hant-TW",
        english = "Hokkien (Taiwan)",
        native = "閩南語（台灣）",
        tag = "nan-Hant-TW",
        speechLanguage = "zh-TW",
        group = "Hokkien"
    ),
    LanguageOption(
        id = "nan-Hans-CN",
        english = "Hokkien (Mainland China)",
        native = "闽南语（中国大陆）",
        tag = "nan-Hans-CN",
        speechLanguage = "zh-TW",
        group = "Hokkien"
    ),
    LanguageOption(
        id = "cdo-Hans-CN",
        english = "Eastern Min (Fuzhou)",
        native = "闽东语（福州）",
        tag = "cdo-Hans-CN",
        speechLanguage = "zh-CN",
        group = "Hokkien"
    ),

    // —— Regional Mandarin & other Han dialect groups ——
    mandarin(
        "dongbei",
        "Northeastern Mandarin (Heilongjiang, Jilin, Liaoning)",
        "东北官话（黑吉辽·内蒙古东部）",
    ),
    mandarin(
        "jingji",
        "Jilu Mandarin (Beijing, Tianjin, Hebei, Shandong)",
        "冀鲁官话（京津冀·山东）",
    ),
    mandarin(
        "jiaoliao",
        "Jiaoliao Mandarin (Shandong & Liaodong peninsulas)",
        "胶辽官话（山东半岛·辽东半岛）",
    ),
    mandarin(
        "zhongyuan",
        "Zhongyuan Mandarin (Henan, Guanzhong, Northern Anhui & Jiangsu)",
        "中原官话（河南·关中·皖北·苏北）",
    ),
    mandarin(
        "lanyin",
        "Lanyin Mandarin (Gansu, Ningxia, Northern Xinjiang)",
        "兰银官话（甘肃·宁夏·北疆）",
    ),
    mandarin(
        "jianghuai",
        "Jianghuai Mandarin (Jiangsu, Anhui, Eastern Hubei)",
        "江淮官话（江苏·安徽·鄂东）",
    ),
    mandarin(
        "sichuan",
        "Southwestern Mandarin (Sichuan, Guizhou, Chongqing)",
        "西南官话（四川·贵州·重庆）",
    ),
    mandarin(
        "yunnan",
        "Southwestern Mandarin (Yunnan)",
        "云南官话",
    ),
    mandarin(
        "jin",
        "Jin (Shanxi, Central Inner Mongolia, Northern Shaanxi)",
        "晋语（山西·内蒙古中部·陕北）",
    ),
    mandarin(
        "wu",
        "Wu (Shanghai, Southern Jiangsu, Zhejiang)",
        "吴语（上海·苏南·浙江）",
    ),
    mandarin(
        "hui",
        "Hui (Southern Anhui, Western Zhejiang)",
        "徽语（皖南·浙西）",
    ),
    mandarin(
        "gan",
        "Gan (Jiangxi, Eastern Hunan, Southeastern Hubei)",
        "赣语（江西·湘东·鄂东南）",
    ),
    mandarin(
        "xiang",
        "Xiang (Hunan)",
        "湘语（湖南）",
    ),
    mandarin(
        "hakka",
        "Hakka (Eastern Guangdong, Western Fujian, Southern Jiangxi, Taiwan)",
        "客家话（粤东·闽西·赣南·台湾）",
    ),
    mandarin(
        "guangxi",
        "Guangxi (Cantonese, Guiliu Mandarin, Zhuang)",
        "广西（粤语·桂柳官话·壮语）",
    ),
    mandarin(
        "pinghua",
        "Pinghua (Guangxi)",
        "平话（广西）",
    ),
    mandarin(
        "zhuang",
        "Zhuang (Western Guangxi)",
        "壮语（广西西部）",
    ),

    // —— Minority languages ——
    LanguageOption(
        id = "bo-CN",
        english = "Tibetan (Ü-Tsang)",
        native = "藏语（卫藏）བོད་སྐད།",
        tag = "bo-CN",
        speechLanguage = "zh-CN",
        group = "Minority languages",
    ),
    LanguageOption(
        id = "adx-CN",
        english = "Tibetan (Amdo)",
        native = "藏语（安多）",
        tag = "adx-CN",
        speechLanguage = "zh-CN",
        group = "Minority languages",
    ),
    LanguageOption(
        id = "khg-CN",
        english = "Tibetan (Khams)",
        native = "藏语（康巴）",
        tag = "khg-CN",
        speechLanguage = "zh-CN",
        group = "Minority languages",
    ),
    LanguageOption(
        id = "mn-Mong-CN",
        english = "Mongolian (Inner Mongolia)",
        native = "蒙古语（内蒙古）Монгол хэл",
        tag = "mn-Mong-CN",
        speechLanguage = "zh-CN",
        group = "Minority languages",
    ),
    LanguageOption(
        id = "ug-CN",
        english = "Uyghur",
        native = "维吾尔语 ئۇيغۇرچە",
        tag = "ug-CN",
        speechLanguage = "zh-CN",
        group = "Minority languages",
    ),
    LanguageOption(
        id = "kk-CN",
        english = "Kazakh (Xinjiang)",
        native = "哈萨克语（新疆）Қазақ тілі",
        tag = "kk-CN",
        speechLanguage = "zh-CN",
        group = "Minority languages",
    ),
    LanguageOption(
        id = "ko-CN",
        english = "Korean (Yanbian)",
        native = "朝鲜语（延边）조선어",
        tag = "ko-CN",
        speechLanguage = "ko-KR",
        group = "Minority languages",
    ),
    LanguageOption(
        id = "za-CN-x-north",
        english = "Zhuang (Northern)",
        native = "壮语（北部）Vahcuengh",
        tag = "za-CN-x-north",
        speechLanguage = "zh-CN",
        group = "Minority languages",
    ),
    LanguageOption(
        id = "za-CN-x-south",
        english = "Zhuang (Southern)",
        native = "壮语（南部）",
        tag = "za-CN-x-south",
        speechLanguage = "zh-CN",
        group = "Minority languages",
    ),
    LanguageOption(
        id = "ii-CN",
        english = "Nuosu Yi (Liangshan)",
        native = "彝语（凉山）ꆈꌠꉙ",
        tag = "ii-CN",
        speechLanguage = "zh-CN",
        group = "Minority languages",
    ),
    LanguageOption(
        id = "hmn-CN",
        english = "Hmong / Miao",
        native = "苗语 Hmoob",
        tag = "hmn-CN",
        speechLanguage = "zh-CN",
        group = "Minority languages",
    ),
    LanguageOption(
        id = "lis-CN",
        english = "Lisu",
        native = "傈僳语 ꓡꓲ-ꓢꓴ",
        tag = "lis-CN",
        speechLanguage = "zh-CN",
        group = "Minority languages",
    ),
    LanguageOption(
        id = "nbf-CN",
        english = "Naxi",
        native = "纳西语 Naqxi",
        tag = "nbf-CN",
        speechLanguage = "zh-CN",
        group = "Minority languages",
    ),

    // —— English ——
    LanguageOption(
        id = "en-GB",
        english = "English (United Kingdom)",
        native = "English (UK)",
        tag = "en-GB",
        speechLanguage = "en-GB",
        group = "English",
    ),
    LanguageOption(
        id = "en-US",
        english = "English (United States)",
        native = "English (US)",
        tag = "en-US",
        speechLanguage = "en-US",
        group = "English",
    ),

    // —— Japanese ——
    LanguageOption(
        id = "ja-JP",
        english = "Japanese (Japan)",
        native = "日本語",
        tag = "ja-JP",
        speechLanguage = "ja-JP",
        group = "Japanese",
    ),

    // —— Korean ——
    LanguageOption(
        id = "ko-KR",
        english = "Korean (Korea)",
        native = "한국어",
        tag = "ko-KR",
        speechLanguage = "ko-KR",
        group = "Korean",
    ),
)

fun findLanguage(id: String): LanguageOption =
    AppLanguages.firstOrNull { it.id == id } ?: AppLanguages.first()

/**
 * 资源 locale：方言没有独立 values 目录，映射到对应字形/地区的资源，
 * 避免回落到英文默认。
 */
fun resourceTagOf(id: String): String = when {
    id.startsWith("zh-Hans-CN-x-") -> "zh-Hans-CN"
    id == "bo-CN" || id == "adx-CN" || id == "khg-CN" -> "bo"
    id == "mn-Mong-CN" -> "mn"
    id == "yue-Hant-HK" || id == "yue-Hant-MO" || id == "zh-Hant-MO" -> "zh-Hant-HK"
    id == "yue-Hans-CN" || id == "nan-Hans-CN" || id == "cdo-Hans-CN" -> "zh-Hans-CN"
    id == "nan-Hant-TW" -> "zh-Hant-TW"
    id.startsWith("za-CN-x-") -> "zh-Hans-CN"
    else -> id
}