/*
 * AI BIRT — сессия работы с дизайном отчёта (агентный режим).
 *
 * Инкапсулирует ReportDesignHandle и командный стек Design API:
 *   - create()/open() — новый дизайн или существующий файл;
 *   - exec(label, op) — КАЖДАЯ мутация в транзакции CommandStack:
 *     startTrans → операция → commit, при исключении — rollback,
 *     и ошибка возвращается модели для самокоррекции;
 *   - validate() — семантические проверки дизайн-модели (getErrorList)
 *     с подсказками (BirtErrorHints);
 *   - saveAtomically() — сериализация во временный файл в каталоге
 *     назначения, резервная копия .bak и атомарная замена (файл никогда
 *     не остаётся «наполовину записанным»);
 *   - манифест — «теневая» спецификация (ADR-0003): всё, что агент
 *     добавил или что обнаружено интроспекцией при открытии; по нему
 *     проверяются перекрёстные ссылки новых фрагментов.
 *
 * Идентификаторы элементов: каждому добавленному элементу присваивается
 * уникальный строковый id (name в терминах BIRT), по нему работают
 * remove_element и интроспекция.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.core.report;

import com.aisql.birt.core.Json;
import com.aisql.birt.core.spec.ReportSpec;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.birt.report.model.api.CommandStack;
import org.eclipse.birt.report.model.api.DesignConfig;
import org.eclipse.birt.report.model.api.DesignElementHandle;
import org.eclipse.birt.report.model.api.DesignEngine;
import org.eclipse.birt.report.model.api.ElementFactory;
import org.eclipse.birt.report.model.api.ModuleHandle;
import org.eclipse.birt.report.model.api.ReportDesignHandle;
import org.eclipse.birt.report.model.api.SessionHandle;
import org.eclipse.birt.report.model.api.SlotHandle;
import org.eclipse.birt.report.model.api.activity.SemanticException;

import com.ibm.icu.util.ULocale;

/**
 * Сессия дизайна: транзакции, валидация, атомарное сохранение, манифест.
 */
public final class DesignSession {

    /** Ошибка операции сессии (текст пригоден для возврата модели). */
    public static class DesignException extends Exception {

        private static final long serialVersionUID = 1L;

        public DesignException(String message) {
            super(message);
        }

        public DesignException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Одна атомарная операция над дизайном. */
    public interface ElementOp {

        /** Применяет изменения (внутри транзакции). */
        void run(ReportDesignHandle design, ElementFactory factory) throws Exception;
    }

    private SessionHandle session;
    private ReportDesignHandle design;
    private ElementFactory factory;

    /** Манифест: «текущее состояние» в терминах спецификации. */
    private String reportName = "report";
    private String reportTitle = "report";
    private ReportSpec.Page page;
    private final List<ReportSpec.Parameter> parameters = new ArrayList<ReportSpec.Parameter>();
    private final List<ReportSpec.DataSource> datasources = new ArrayList<ReportSpec.DataSource>();
    private final List<ReportSpec.DataSet> datasets = new ArrayList<ReportSpec.DataSet>();
    private final List<ReportSpec.BodyElement> body = new ArrayList<ReportSpec.BodyElement>();
    /** id элемента → имя BIRT (для remove_element и интроспекции). */
    private final Map<String, String> elementNames = new LinkedHashMap<String, String>();

    private final String sessionId;
    private int opCount;
    private int rollbackCount;
    private int labelSeq;
    private int tableSeq;
    private int gridSeq;
    private int imageSeq;
    private int listSeq;
    private int chartSeq;

    public DesignSession(String sessionId) {
        this.sessionId = sessionId == null ? "s" : sessionId;
    }

    // ------------------------------------------------------------------
    // Жизненный цикл
    // ------------------------------------------------------------------

    /** Создаёт новый пустой дизайн. */
    public synchronized void create() throws DesignException {
        try {
            closeQuietly();
            session = new DesignEngine(new DesignConfig()).newSessionHandle(ULocale.ENGLISH);
            design = session.createDesign();
            factory = design.getElementFactory();
        } catch (Throwable t) {
            throw new DesignException("не удалось создать новый дизайн: " + t.getMessage(), t);
        }
    }

    /**
     * Открывает существующий .rptdesign (для правки). Манифест наполняется
     * из структуры файла (ReportIntrospector), пароль источника НЕ читается
     * в манифест — он спрашивается у пользователя при сборке.
     */
    public synchronized void open(File file) throws DesignException {
        if (file == null || !file.isFile()) {
            throw new DesignException("файл отчёта не найден: " + file);
        }
        try {
            closeQuietly();
            session = new DesignEngine(new DesignConfig()).newSessionHandle(ULocale.ENGLISH);
            design = session.openDesign(file.getAbsolutePath());
            factory = design.getElementFactory();
            reportName = stripExt(file.getName());
            reportTitle = reportName;
            seedManifestFromDesign();
        } catch (Throwable t) {
            throw new DesignException("не удалось открыть отчёт: " + t.getMessage(), t);
        }
    }

    public synchronized boolean isOpen() {
        return design != null;
    }

    public synchronized ReportDesignHandle design() {
        return design;
    }

    public synchronized ElementFactory factory() {
        return factory;
    }

    public synchronized void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        if (session != null) {
            try {
                session.closeAll(false);
            } catch (Throwable ignored) {
                // закрытие сессии не критично
            }
        }
        session = null;
        design = null;
        factory = null;
    }

    // ------------------------------------------------------------------
    // Транзакции
    // ------------------------------------------------------------------

    /**
     * Выполняет операцию в транзакции командного стека. При исключении —
     * откат и DesignException с сообщением, пригодным для модели.
     */
    public synchronized void exec(String label, ElementOp op) throws DesignException {
        requireOpen();
        opCount++;
        CommandStack stack = design.getCommandStack();
        stack.startTrans("ai-birt: " + label);
        try {
            op.run(design, factory);
            stack.commit();
        } catch (Throwable t) {
            rollbackCount++;
            try {
                stack.rollback();
            } catch (Throwable ignored) {
                // откат не удался — сессию лучше закрыть вызывающему коду
            }
            throw new DesignException(label + ": " + describe(t), t);
        }
    }

    /**
     * Полный откат изменений сессии. Особенность BIRT: rollbackAll()
     * откатывает только незакоммиченные транзакции, поэтому закоммиченные
     * снимаются явными undo() до дна стека (проверено на 4.7 и 4.25).
     */
    public synchronized void rollbackAll() {
        if (design != null) {
            try {
                design.getCommandStack().rollbackAll();
            } catch (Throwable ignored) {
                // стек уже пуст
            }
            CommandStack stack = design.getCommandStack();
            int guard = 10000;
            while (stack.canUndo() && guard-- > 0) {
                try {
                    stack.undo();
                } catch (Throwable t) {
                    break;
                }
            }
        }
    }

    private void requireOpen() throws DesignException {
        if (design == null) {
            throw new DesignException("дизайн не открыт (сначала report_new или report_open)");
        }
    }

    private static String describe(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isEmpty()) {
            m = t.getClass().getSimpleName();
        }
        return m;
    }

    // ------------------------------------------------------------------
    // Валидация
    // ------------------------------------------------------------------

    /** Результат валидации. */
    public static final class ValidationResult {

        /** true — ошибок нет. */
        public final boolean ok;
        /** Список проблем (уже с подсказками). */
        public final List<String> problems;
        /** Счётчик добавленных подсказок. */
        public final int hints;

        ValidationResult(boolean ok, List<String> problems, int hints) {
            this.ok = ok;
            this.problems = problems;
            this.hints = hints;
        }
    }

    /**
     * Семантическая проверка дизайн-модели BIRT (getErrorList) с подсказками.
     * БIRT копит ошибки в списке модуля; часть из них — предупреждения
     * (severity WARNING), часть — ошибки (severity ERROR): отчёт считается
     * некорректным, только если есть ERROR.
     */
    public synchronized ValidationResult validate() {
        List<String> problems = new ArrayList<String>();
        int hints = 0;
        if (design == null) {
            problems.add("дизайн не открыт");
            return new ValidationResult(false, problems, 0);
        }
        List<?> errors = design.getErrorList();
        if (errors != null) {
            for (Object e : errors) {
                String text = String.valueOf(e);
                String hint = BirtErrorHints.hint(text);
                if (hint != null) {
                    problems.add(text + " — подсказка: " + hint);
                    hints++;
                } else {
                    problems.add(text);
                }
            }
        }
        boolean hasError = false;
        for (Object e : errors == null ? new ArrayList<Object>() : errors) {
            if (isErrorSeverity(e)) {
                hasError = true;
                break;
            }
        }
        return new ValidationResult(!hasError, problems, hints);
    }

    private static boolean isErrorSeverity(Object error) {
        try {
            java.lang.reflect.Method m = error.getClass().getMethod("getSeverity");
            Object sev = m.invoke(error);
            // ErrorSeverity.ERROR = 4 (org.eclipse.birt.report.model.api.ErrorSeverity)
            return sev instanceof Integer && ((Integer) sev).intValue() == 4;
        } catch (Exception e) {
            // не удалось определить серьёзность — считаем ошибкой
            return true;
        }
    }

    // ------------------------------------------------------------------
    // Сохранение
    // ------------------------------------------------------------------

    /**
     * Сохраняет дизайн в файл атомарно: сначала сериализация в XML
     * (память → временный файл в каталоге назначения), затем замена
     * целевого файла через ATOMIC_MOVE. Прежняя версия сохраняется
     * рядом как .bak.
     *
     * @return абсолютный путь сохранённого файла
     */
    public synchronized File saveAtomically(File target) throws DesignException {
        requireOpen();
        if (target == null) {
            throw new DesignException("не задан файл для сохранения");
        }
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new DesignException("не удалось создать каталог " + dir.getAbsolutePath());
        }
        String xml;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
            design.serialize(out);
            xml = new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            throw new DesignException("не удалось сериализовать отчёт: " + describe(t), t);
        }
        try {
            Path targetPath = target.toPath();
            Path tmp = dir.toPath().resolve(target.getName() + ".ai-birt.tmp");
            Files.write(tmp, xml.getBytes(StandardCharsets.UTF_8));
            if (Files.exists(targetPath)) {
                Path bak = dir.toPath().resolve(target.getName() + ".bak");
                try {
                    Files.move(targetPath, bak, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    // резервная копия не критична
                }
            }
            try {
                Files.move(tmp, targetPath, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
            design.setFileName(target.getAbsolutePath());
            return target;
        } catch (IOException e) {
            throw new DesignException("не удалось сохранить файл отчёта: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // Манифест (теневая спецификация)
    // ------------------------------------------------------------------

    /** Свежий снимок манифеста как спецификация (для перекрёстных ссылок). */
    public synchronized ReportSpec manifest() {
        return ReportSpec.compose(reportName, reportTitle, page,
            parameters, datasources, datasets, body);
    }

    public synchronized List<ReportSpec.Parameter> manifestParameters() {
        return parameters;
    }

    public synchronized List<ReportSpec.DataSource> manifestDataSources() {
        return datasources;
    }

    public synchronized List<ReportSpec.DataSet> manifestDataSets() {
        return datasets;
    }

    public synchronized String reportName() {
        return reportName;
    }

    public synchronized void setReportName(String name) {
        if (name != null && !name.trim().isEmpty()) {
            this.reportName = name.trim();
        }
    }

    public synchronized String reportTitle() {
        return reportTitle;
    }

    public synchronized void setReportTitle(String title) {
        if (title != null && !title.trim().isEmpty()) {
            this.reportTitle = title.trim();
        }
    }

    public synchronized ReportSpec.Page manifestPage() {
        return page;
    }

    public synchronized void setManifestPage(ReportSpec.Page page) {
        this.page = page;
    }

    public synchronized void addManifestParameter(ReportSpec.Parameter p) {
        parameters.add(p);
    }

    public synchronized void addManifestDataSource(ReportSpec.DataSource ds) {
        datasources.add(ds);
    }

    public synchronized void addManifestDataSet(ReportSpec.DataSet ds) {
        datasets.add(ds);
    }

    public synchronized void addManifestBody(ReportSpec.BodyElement el, String elementName) {
        body.add(el);
        registerElementName(elementName);
    }

    /** Запоминает имя BIRT для элемента (интроспекция/remove). */
    public synchronized void registerElementName(String elementName) {
        if (elementName != null && !elementName.isEmpty()) {
            elementNames.put(elementName, elementName);
        }
    }

    public synchronized Map<String, String> elementNames() {
        return new LinkedHashMap<String, String>(elementNames);
    }

    /** Счётчики статистики сессии. */
    public synchronized int opCount() {
        return opCount;
    }

    public synchronized int rollbackCount() {
        return rollbackCount;
    }

    public String sessionId() {
        return sessionId;
    }

    // ------------------------------------------------------------------
    // Имена элементов
    // ------------------------------------------------------------------

    public synchronized String nextName(String prefix) {
        if ("label".equals(prefix)) {
            return "label_" + (++labelSeq);
        }
        if ("table".equals(prefix)) {
            return "table_" + (++tableSeq);
        }
        if ("grid".equals(prefix)) {
            return "grid_" + (++gridSeq);
        }
        if ("image".equals(prefix)) {
            return "image_" + (++imageSeq);
        }
        if ("list".equals(prefix)) {
            return "list_" + (++listSeq);
        }
        if ("chart".equals(prefix)) {
            return "chart_" + (++chartSeq);
        }
        return prefix + "_" + (++opCount);
    }

    /**
     * Наполняет манифест из открытого дизайна: источники, наборы, параметры,
     * элементы тела. Вызывается из open(); структурная информация только
     * для перекрёстных ссылок и интроспекции (данные не читаются).
     */
    private void seedManifestFromDesign() {
        parameters.clear();
        datasources.clear();
        datasets.clear();
        body.clear();
        ReportIntrospector.seed(design, this);
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Элемент по имени BIRT (или null). */
    public synchronized DesignElementHandle findElement(String name) {
        if (design == null || name == null || name.isEmpty()) {
            return null;
        }
        DesignElementHandle h = design.findElement(name);
        if (h != null) {
            return h;
        }
        // поиск по телу отчёта (безымянные элементы)
        SlotHandle bodySlot = design.getBody();
        for (int i = 0; i < bodySlot.getCount(); i++) {
            DesignElementHandle el = bodySlot.get(i);
            if (name.equals(el.getName())) {
                return el;
            }
        }
        return null;
    }

    /** JSON-структура отчёта для инструмента report_inspect. */
    public synchronized String inspectJson() {
        if (design == null) {
            return "{\"error\":\"дизайн не открыт\"}";
        }
        return ReportIntrospector.toJson(design, manifest());
    }
}
