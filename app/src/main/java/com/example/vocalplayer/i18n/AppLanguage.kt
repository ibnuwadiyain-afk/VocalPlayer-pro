package com.example.vocalplayer.i18n

import androidx.compose.ui.unit.LayoutDirection
import java.util.Locale

/**
 * Supported UI languages in VocalPlayer.
 */
enum class AppLanguage(
    val code: String,
    val nativeName: String,
    val englishName: String,
    val isRtl: Boolean
) {
    ENGLISH("en", "English", "English", false),
    ARABIC("ar", "العربية", "Arabic", true),
    SPANISH("es", "Español", "Spanish", false),
    FRENCH("fr", "Français", "French", false),
    INDONESIAN("id", "Bahasa Indonesia", "Indonesian", false),
    RUSSIAN("ru", "Русский", "Russian", false);

    companion object {
        fun fromCode(code: String): AppLanguage {
            return values().firstOrNull { it.code.equals(code, ignoreCase = true) } ?: ENGLISH
        }
    }
}

/**
 * Comprehensive Multilingual UI Dictionary for VocalPlayer.
 * Provides instant in-app locale switching without requiring app restart or complex system changes.
 */
object AppStrings {

    fun get(key: String, language: AppLanguage): String {
        return translations[language]?.get(key)
            ?: translations[AppLanguage.ENGLISH]?.get(key)
            ?: key
    }

    private val translations = mapOf(
        AppLanguage.ENGLISH to mapOf(
            // App Bar & Main HUD
            "app_title" to "VocalPlayer",
            "subtitle_offline" to "Offline Neural Engine",
            "import_web" to "Import Web Media",
            "export_video" to "Export Video",
            "models" to "Models",
            "settings" to "Settings",
            "language" to "Language",

            // Playback & Surface
            "vocal_stem_active" to "VOCAL STEM ACTIVE",
            "original_audio" to "ORIGINAL AUDIO",
            "live_stream" to "⚡ Live Stream",
            "no_media_loaded" to "No media selected",
            "offline_player_desc" to "Select any local video or audio file to extract synchronized vocals in real time.",
            "open_file" to "Open File",
            "import_link" to "Import Link",
            "spleeter_extraction" to "Spleeter Neural Extraction",
            "auto_play_hint" to "Auto-plays once first chunk is ready",

            // Vocal Toggle & Sliders
            "vocal_isolated" to "Vocal (Isolated)",
            "original_mix" to "Original Mix",
            "instruments_muted" to "Instruments Muted",
            "full_audio_track" to "Full Audio Track",
            "vocal_boost" to "VOCAL BOOST",
            "playback_speed" to "PLAYBACK SPEED",
            "master_volume" to "MASTER VOLUME",

            // Offline Extraction Card
            "offline_lead_vocal" to "Offline Lead Vocal Engine",
            "chunk_streaming" to "Chunk Streaming",
            "ready_cached" to "Ready (Cached)",
            "extracting" to "Extracting...",
            "extract_progress" to "Extracting Vocals",
            "elapsed" to "Elapsed",
            "cache_size" to "Cache Size",
            "re_extract" to "Re-Extract Vocals",
            "delete_cache" to "Delete Cache",

            // Export Dialog
            "export_title" to "Export Video (Instruments Muted)",
            "export_subtitle" to "Zero quality loss remuxed with isolated vocals",
            "export_ready_instant" to "Pipelined Export Ready (Instant)",
            "delete_original_checkbox" to "Delete Original Video after successful export",
            "delete_original_warning" to "Will permanently remove the original video to reclaim disk space.",
            "start_export" to "Export Video (Muted Instruments)",
            "play_exported" to "Play Exported Video",
            "share_exported" to "Share Video",
            "export_complete" to "Export Complete!",
            "cancel" to "Cancel",
            "close" to "Close",

            // Web Import Dialog & Multitask Downloads
            "import_dialog_title" to "Import Web Media",
            "import_dialog_subtitle" to "YouTube, TikTok, Vimeo, Direct URLs & HLS",
            "paste_placeholder" to "Paste link (e.g. YouTube, Vimeo, TikTok, MP4)...",
            "paste_button" to "PASTE",
            "inspect_button" to "Inspect Media & Resolutions",
            "inspecting" to "Inspecting stream & qualities...",
            "select_quality" to "SELECT STREAM QUALITY / FORMAT:",
            "fast" to "FAST",
            "download_and_load" to "Download & Load into VocalPlayer",
            "download_in_background" to "Download in Background (Multitask)",
            "active_downloads" to "Background Downloads",
            "no_active_downloads" to "No active background downloads",
            "downloading" to "Downloading",
            "queued" to "Queued",
            "completed" to "Completed",
            "failed" to "Failed",
            "load_into_player" to "Load into Player",
            "cancel_download" to "Cancel",
            "clear_completed" to "Clear Completed",

            // Settings & Languages
            "engine_settings" to "Engine Settings",
            "separation_mode" to "SEPARATION MODE",
            "cpu_threads" to "CPU INFERENCE THREADS",
            "offline_notice" to "100% Offline: All neural processing and video decoding occur directly on device.",
            "select_language" to "UI LANGUAGE / اللغة",
            "arabic_export_support" to "Arabic file name support is enabled for exports.",
            "done" to "Done"
        ),

        AppLanguage.ARABIC to mapOf(
            // App Bar & Main HUD
            "app_title" to "فوكال بلاير",
            "subtitle_offline" to "محرك الذكاء الاصطناعي بدون إنترنت",
            "import_web" to "استيراد من الويب",
            "export_video" to "تصدير الفيديو",
            "models" to "النماذج",
            "settings" to "الإعدادات",
            "language" to "اللغة",

            // Playback & Surface
            "vocal_stem_active" to "الصوت البشري مفعل",
            "original_audio" to "الصوت الأصلي",
            "live_stream" to "⚡ بث مباشر",
            "no_media_loaded" to "لم يتم اختيار وسائط",
            "offline_player_desc" to "اختر أي ملف فيديو أو صوت محلي لعزل الصوت البشري وإزالة الموسيقى في الوقت الفعلي.",
            "open_file" to "فتح ملف",
            "import_link" to "استيراد رابط",
            "spleeter_extraction" to "عزل الصوت بالذكاء الاصطناعي",
            "auto_play_hint" to "التشغيل التلقائي بمجرد جهوزية أول جزء",

            // Vocal Toggle & Sliders
            "vocal_isolated" to "صوت نقي (معزول)",
            "original_mix" to "المقطع الأصلي",
            "instruments_muted" to "الموسيقى مكتومة",
            "full_audio_track" to "كامل المسار الصوتي",
            "vocal_boost" to "تضخيم الصوت",
            "playback_speed" to "سرعة التشغيل",
            "master_volume" to "مستوى الصوت العام",

            // Offline Extraction Card
            "offline_lead_vocal" to "محرك عزل الصوت النقي بدون إنترنت",
            "chunk_streaming" to "بث الأجزاء المعالجة",
            "ready_cached" to "جاهز (محفوظ مؤقتاً)",
            "extracting" to "جارِ العزل...",
            "extract_progress" to "جارِ عزل الصوت البشري",
            "elapsed" to "الوقت المنقضي",
            "cache_size" to "حجم الذاكرة المؤقتة",
            "re_extract" to "إعادة عزل الصوت",
            "delete_cache" to "حذف الذاكرة المؤقتة",

            // Export Dialog
            "export_title" to "تصدير الفيديو (بدون موسيقى)",
            "export_subtitle" to "دمج فوري بدون فقدان للجودة مع الصوت البشري المعزول",
            "export_ready_instant" to "التصدير المتزامن جاهز (فوري)",
            "delete_original_checkbox" to "حذف الفيديو الأصلي بعد نجاح التصدير",
            "delete_original_warning" to "سيتم حذف الفيديو الأصلي نهائياً لتوفير مساحة التخزين.",
            "start_export" to "تصدير الفيديو (بدون موسيقى)",
            "play_exported" to "تشغيل الفيديو المصدر",
            "share_exported" to "مشاركة الفيديو",
            "export_complete" to "اكتمل التصدير بنجاح!",
            "cancel" to "إلغاء",
            "close" to "إغلاق",

            // Web Import Dialog & Multitask Downloads
            "import_dialog_title" to "استيراد وسائط من الويب",
            "import_dialog_subtitle" to "يوتيوب، تيك توك، فيميو، روابط مباشرة وبث HLS",
            "paste_placeholder" to "الصق الرابط (يوتيوب، تيك توك، فيميو، MP4)...",
            "paste_button" to "لصق",
            "inspect_button" to "فحص الوسائط والجودات",
            "inspecting" to "جارِ فحص الرابط وخيارات الجودة...",
            "select_quality" to "اختر دقة وجودة المقطع:",
            "fast" to "سريع",
            "download_and_load" to "تنزيل وتشغيل فوري في المشغل",
            "download_in_background" to "تنزيل في الخلفية (مهام متعددة)",
            "active_downloads" to "تنزيلات الخلفية",
            "no_active_downloads" to "لا توجد تنزيلات نشطة في الخلفية",
            "downloading" to "جارِ التنزيل",
            "queued" to "في الانتظار",
            "completed" to "مكتمل",
            "failed" to "فشل التنزيل",
            "load_into_player" to "تشغيل في التطبيق",
            "cancel_download" to "إلغاء",
            "clear_completed" to "مسح المكتمل",

            // Settings & Languages
            "engine_settings" to "إعدادات المحرك",
            "separation_mode" to "نمط عزل الصوت",
            "cpu_threads" to "أنوية المعالجة (CPU Threads)",
            "offline_notice" to "يعمل 100% بدون إنترنت: تتم المعالجة وعزل الصوت بالكامل على جهازك.",
            "select_language" to "لغة الواجهة (UI Language)",
            "arabic_export_support" to "دعم كامل للأسماء العربية والخط العربي عند التصدير وحفظ الملفات.",
            "done" to "تم"
        ),

        AppLanguage.SPANISH to mapOf(
            "app_title" to "VocalPlayer",
            "subtitle_offline" to "Motor Neuronal Sin Conexión",
            "import_web" to "Importar de la Web",
            "export_video" to "Exportar Video",
            "models" to "Modelos",
            "settings" to "Ajustes",
            "language" to "Idioma",
            "vocal_stem_active" to "PISTA VOCAL ACTIVA",
            "original_audio" to "AUDIO ORIGINAL",
            "live_stream" to "⚡ Transmisión en Vivo",
            "no_media_loaded" to "Sin multimedia seleccionada",
            "offline_player_desc" to "Selecciona cualquier archivo de video o audio para aislar voces en tiempo real.",
            "open_file" to "Abrir Archivo",
            "import_link" to "Importar Enlace",
            "vocal_isolated" to "Voz (Aislada)",
            "original_mix" to "Mezcla Original",
            "instruments_muted" to "Instrumentos Silenciados",
            "export_title" to "Exportar Video (Sin Instrumentos)",
            "delete_original_checkbox" to "Eliminar video original tras exportar",
            "download_in_background" to "Descargar en Segundo Plano",
            "active_downloads" to "Descargas en Segundo Plano",
            "done" to "Listo"
        ),

        AppLanguage.FRENCH to mapOf(
            "app_title" to "VocalPlayer",
            "subtitle_offline" to "Moteur Neuronal Hors-ligne",
            "import_web" to "Importer du Web",
            "export_video" to "Exporter la Vidéo",
            "models" to "Modèles",
            "settings" to "Paramètres",
            "language" to "Langue",
            "vocal_stem_active" to "PISTE VOCALE ACTIVE",
            "original_audio" to "AUDIO ORIGINAL",
            "live_stream" to "⚡ Flux en Direct",
            "no_media_loaded" to "Aucun média sélectionné",
            "offline_player_desc" to "Sélectionnez une vidéo ou un fichier audio pour isoler les voix en temps réel.",
            "open_file" to "Ouvrir un Fichier",
            "import_link" to "Importer un Lien",
            "vocal_isolated" to "Voix (Isolée)",
            "original_mix" to "Mix Original",
            "instruments_muted" to "Instruments Coupés",
            "export_title" to "Exporter la Vidéo (Sans Musique)",
            "delete_original_checkbox" to "Supprimer la vidéo d'origine après l'exportation",
            "download_in_background" to "Télécharger en Arrière-plan",
            "active_downloads" to "Téléchargements en Arrière-plan",
            "done" to "Terminé"
        ),

        AppLanguage.INDONESIAN to mapOf(
            "app_title" to "VocalPlayer",
            "subtitle_offline" to "Mesin AI Luring",
            "import_web" to "Impor dari Web",
            "export_video" to "Ekspor Video",
            "models" to "Model",
            "settings" to "Pengaturan",
            "language" to "Bahasa",
            "vocal_stem_active" to "VOKAL AKTIF",
            "original_audio" to "AUDIO ASLI",
            "live_stream" to "⚡ Streaming Langsung",
            "no_media_loaded" to "Tidak ada media yang dipilih",
            "open_file" to "Buka Berkas",
            "import_link" to "Impor Tautan",
            "vocal_isolated" to "Vokal (Terisolasi)",
            "original_mix" to "Audio Asli",
            "instruments_muted" to "Instrumen Dibisukan",
            "export_title" to "Ekspor Video (Tanpa Musik)",
            "delete_original_checkbox" to "Hapus video asli setelah ekspor",
            "download_in_background" to "Unduh di Latar Belakang",
            "active_downloads" to "Unduhan Latar Belakang",
            "done" to "Selesai"
        ),

        AppLanguage.RUSSIAN to mapOf(
            "app_title" to "VocalPlayer",
            "subtitle_offline" to "Офлайн Нейросетевой Движок",
            "import_web" to "Импорт из Сети",
            "export_video" to "Экспорт Видео",
            "models" to "Модели",
            "settings" to "Настройки",
            "language" to "Язык",
            "vocal_stem_active" to "ВОКАЛ АКТИВЕН",
            "original_audio" to "ОРИГИНАЛЬНЫЙ ЗВУК",
            "live_stream" to "⚡ Прямой Поток",
            "no_media_loaded" to "Медиафайл не выбран",
            "open_file" to "Открыть Файл",
            "import_link" to "Импорт Ссылки",
            "vocal_isolated" to "Вокал (Изолирован)",
            "original_mix" to "Оригинал",
            "instruments_muted" to "Музыка Заглушена",
            "export_title" to "Экспорт Видео (Без Музыки)",
            "delete_original_checkbox" to "Удалить исходное видео после экспорта",
            "download_in_background" to "Скачать в Фоне",
            "active_downloads" to "Фоновые Загрузки",
            "done" to "Готово"
        )
    )
}
