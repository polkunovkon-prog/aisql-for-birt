/*
 * AI BIRT — панель чата «ИИ-конструктор отчётов».
 *
 * Чат с локальной моделью через OpenAI-совместимый сервер; запросы
 * выполняются в фоновых Job, интерфейс не блокируется. Полный цикл:
 *   «Создать отчёт…»    — ТЗ → спецификация (модель);
 *   «Собрать .rptdesign…» — спецификация → файл отчёта (Design API);
 *   «Просмотр…»         — предпросмотр PDF системным просмотрщиком;
 *   «Правка отчёта…»    — замечание → обновлённая спецификация →
 *                         применение к файлу (с резервной копией и откатом);
 *   «Схема БД…»         — чтение метаданных (имена таблиц/колонок) в ТЗ.
 *
 * Настройки — ~/.ai-birt/ai-birt.properties (см. страницу настроек).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.ui;

import com.aisql.birt.core.AuditLog;
import com.aisql.birt.core.BirtSettings;
import com.aisql.birt.core.ChatHistory;
import com.aisql.birt.core.ChatMessage;
import com.aisql.birt.core.LlmClient;
import com.aisql.birt.core.jdbc.DbSchema;
import com.aisql.birt.core.report.BuiltReports;
import com.aisql.birt.core.report.ReportBuilder;
import com.aisql.birt.core.report.ReportPreview;
import com.aisql.birt.core.spec.ReportSpec;
import com.aisql.birt.core.spec.SpecDiff;
import com.aisql.birt.core.spec.SpecPrompts;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;

import org.eclipse.birt.report.engine.api.IReportEngine;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowData;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IPathEditorInput;
import org.eclipse.ui.IURIEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.ide.FileStoreEditorInput;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.ViewPart;

/** Панель чата с локальной ИИ-моделью и действиями над отчётом. */
public class ChatView extends ViewPart {

    public static final String VIEW_ID = "com.aisql.birt.ui.chatView";

    /** Пониженная температура для генерации спецификации: нужен строгий JSON. */
    private static final double SPEC_TEMPERATURE = 0.2d;

    /** Идентификатор редактора отчётов BIRT (для открытия файлов вне рабочей области). */
    private static final String REPORT_EDITOR_ID =
        "org.eclipse.birt.report.designer.ui.editors.ReportEditor";

    private Display display;
    private StyledText transcript;
    private Text input;
    private Button sendButton;
    private Button specButton;
    private Button editButton;
    private Button buildButton;
    private Button previewButton;
    private Button schemaButton;
    private Button rollbackButton;
    private Label modelLabel;
    private Label statusLabel;

    private final ChatHistory history = new ChatHistory(ChatHistory.DEFAULT_MAX_CHARS);
    private volatile boolean busy;
    private volatile ReportSpec pendingSpec;
    private volatile BuildState lastBuild;
    private volatile EditState lastEdit;

    /** Последний собранный в этой сессии отчёт. */
    private static final class BuildState {
        final File file;
        final ReportSpec spec;
        final long mtime;
        final long size;
        final String dbPassword;

        BuildState(File file, ReportSpec spec, String dbPassword) {
            this.file = file;
            this.spec = spec;
            this.mtime = file.lastModified();
            this.size = file.length();
            this.dbPassword = dbPassword;
        }

        /** Файл с тех пор не менялся (и существует). */
        boolean isFresh() {
            return file.isFile() && file.lastModified() == mtime && file.length() == size;
        }
    }

    /** Последняя правка отчёта (прежняя версия — для отката). */
    private static final class EditState {
        final File file;
        final File backup;
        final ReportSpec prevSpec;

        EditState(File file, File backup, ReportSpec prevSpec) {
            this.file = file;
            this.backup = backup;
            this.prevSpec = prevSpec;
        }
    }

    /** Пара «файл отчёта + спецификация» для правки/просмотра. */
    private static final class Target {
        final File file;
        final ReportSpec spec;

        Target(File file, ReportSpec spec) {
            this.file = file;
            this.spec = spec;
        }
    }

    @Override
    public void createPartControl(Composite parent) {
        display = parent.getDisplay();
        parent.setLayout(new GridLayout(1, false));

        createHeader(parent);
        createActions(parent);
        createTranscript(parent);
        createInputRow(parent);

        statusLabel = new Label(parent, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));

        appendWelcome();
        updateModelLabel();
        updateActionButtons();
        setStatusText("Готово. Отправка — Ctrl+Enter.");
    }

    private void createHeader(Composite parent) {
        Composite header = new Composite(parent, SWT.NONE);
        header.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        RowLayout layout = new RowLayout(SWT.HORIZONTAL);
        layout.spacing = 8;
        layout.center = true;
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        header.setLayout(layout);

        modelLabel = new Label(header, SWT.NONE);
        modelLabel.setLayoutData(new RowData());

        Label spacer = new Label(header, SWT.NONE);
        spacer.setLayoutData(new RowData(1, 1));

        Button settingsButton = new Button(header, SWT.PUSH);
        settingsButton.setText("Настройки…");
        settingsButton.setToolTipText("Адрес сервера, модель, ключ доступа, подключение к базе");
        settingsButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                openSettings();
            }
        });

        Button clearButton = new Button(header, SWT.PUSH);
        clearButton.setText("Очистить чат");
        clearButton.setToolTipText("Сбросить историю диалога");
        clearButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                history.clear();
                transcript.setText("");
                appendWelcome();
                setStatusText("Чат очищен. История модели сброшена.");
            }
        });
    }

    private void createActions(Composite parent) {
        Composite actions = new Composite(parent, SWT.NONE);
        actions.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        RowLayout layout = new RowLayout(SWT.HORIZONTAL);
        layout.spacing = 6;
        layout.center = true;
        layout.wrap = true;
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        actions.setLayout(layout);

        specButton = new Button(actions, SWT.PUSH);
        specButton.setText("Создать отчёт…");
        specButton.setToolTipText("Описание отчёта из поля ввода → спецификация (модель)");
        specButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                generateSpec();
            }
        });

        editButton = new Button(actions, SWT.PUSH);
        editButton.setText("Правка отчёта…");
        editButton.setToolTipText("Замечание из поля ввода → обновлённая спецификация → "
            + "применение к собранному отчёту (с резервной копией)");
        editButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                editReport();
            }
        });

        buildButton = new Button(actions, SWT.PUSH);
        buildButton.setText("Собрать .rptdesign…");
        buildButton.setToolTipText("Собрать файл отчёта из готовой спецификации");
        buildButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                buildReport();
            }
        });

        previewButton = new Button(actions, SWT.PUSH);
        previewButton.setText("Просмотр…");
        previewButton.setToolTipText("Выполнить отчёт и открыть PDF (без настройки браузера Eclipse)");
        previewButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                previewReport();
            }
        });

        schemaButton = new Button(actions, SWT.PUSH);
        schemaButton.setText("Схема БД…");
        schemaButton.setToolTipText("Вставить в ТЗ имена таблиц и колонок из базы (только метаданные)");
        schemaButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                readSchema();
            }
        });

        rollbackButton = new Button(actions, SWT.PUSH);
        rollbackButton.setText("Отменить правку");
        rollbackButton.setToolTipText("Вернуть прежнюю версию отчёта из резервной копии");
        rollbackButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                rollbackEdit();
            }
        });
    }

    private void createTranscript(Composite parent) {
        transcript = new StyledText(parent,
            SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.WRAP | SWT.READ_ONLY);
        transcript.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        transcript.setMargins(6, 6, 6, 6);
    }

    private void createInputRow(Composite parent) {
        Composite row = new Composite(parent, SWT.NONE);
        row.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        row.setLayout(layout);

        input = new Text(row, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        GridData inputData = new GridData(SWT.FILL, SWT.FILL, true, false);
        inputData.heightHint = 64;
        input.setLayoutData(inputData);
        input.setToolTipText("Введите вопрос, ТЗ отчёта или замечание к правке; отправка — Ctrl+Enter");
        input.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if ((e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR)
                        && (e.stateMask & SWT.CTRL) != 0) {
                    e.doit = false;
                    send();
                }
            }
        });

        sendButton = new Button(row, SWT.PUSH);
        sendButton.setText("Отправить\n(Ctrl+Enter)");
        sendButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        sendButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                send();
            }
        });
    }

    @Override
    public void setFocus() {
        if (input != null && !input.isDisposed()) {
            input.setFocus();
        }
    }

    // ------------------------------------------------------------------
    // Отправка запроса
    // ------------------------------------------------------------------

    private void send() {
        if (busy || input == null || input.isDisposed()) {
            return;
        }
        String raw = input.getText();
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        final String question = raw.trim();
        input.setText("");

        appendParagraph("Вы:", question, false);
        history.add(ChatMessage.user(question));

        final LlmClient client = LlmClient.fromSettings();
        final String model = client.getModel();
        final List<ChatMessage> apiMessages = history.buildApiMessages(BirtSettings.systemPrompt());
        final double temperature = BirtSettings.temperature();
        final int maxTokens = BirtSettings.maxTokens();

        setBusy(true, "Модель «" + model + "» отвечает…");

        Job job = new Job("Запрос к ИИ-модели (BIRT)") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                long started = System.currentTimeMillis();
                try {
                    String reply = client.chat(apiMessages, temperature, maxTokens);
                    long ms = System.currentTimeMillis() - started;
                    history.add(ChatMessage.assistant(reply));
                    AuditLog.append("chat", "answered", "OK", ms, model, null,
                        "вопрос=" + question.length() + " симв.; ответ=" + reply.length() + " симв.");
                    uiAppend("ИИ (" + model + "):", reply, false);
                    setBusy(false, "Готово (" + seconds(ms) + "). Отправка — Ctrl+Enter.");
                } catch (Exception e) {
                    long ms = System.currentTimeMillis() - started;
                    String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    AuditLog.append("chat", "error", "ERROR", ms, model, message,
                        "вопрос=" + question.length() + " симв.");
                    uiAppend("Ошибка:", message, true);
                    setBusy(false, "Ошибка запроса. Проверьте настройки и повторите.");
                }
                return Status.OK_STATUS;
            }
        };
        job.setUser(true);
        job.schedule();
    }

    // ------------------------------------------------------------------
    // Создание отчёта: спецификация от модели и сборка .rptdesign
    // ------------------------------------------------------------------

    /** Отправляет ТЗ из поля ввода модели и разбирает ответ как спецификацию. */
    private void generateSpec() {
        if (busy || input == null || input.isDisposed()) {
            return;
        }
        final String tz = input.getText() == null ? "" : input.getText().trim();
        if (tz.isEmpty()) {
            setStatusText("Опишите отчёт в поле ввода, затем нажмите «Создать отчёт…».");
            beepAndFocusInput();
            return;
        }
        input.setText("");
        appendParagraph("Вы (ТЗ отчёта):", tz, false);

        final LlmClient client = LlmClient.fromSettings();
        final String model = client.getModel();
        final List<ChatMessage> apiMessages = new ArrayList<ChatMessage>();
        apiMessages.add(ChatMessage.system(SpecPrompts.systemInstruction()));
        apiMessages.add(ChatMessage.user(SpecPrompts.userMessage(tz)));
        final int maxTokens = BirtSettings.maxTokens();

        setBusy(true, "Модель «" + model + "» готовит спецификацию отчёта…");

        Job job = new Job("Спецификация отчёта (BIRT)") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                long started = System.currentTimeMillis();
                try {
                    String reply = client.chat(apiMessages, SPEC_TEMPERATURE, maxTokens);
                    long ms = System.currentTimeMillis() - started;
                    ReportSpec spec = ReportSpec.fromLlmReply(reply);
                    pendingSpec = spec;
                    AuditLog.append("report", "spec", "OK", ms, model, null,
                        "имя=" + spec.name() + "; ответ=" + reply.length() + " симв.");
                    uiAppend("ИИ — спецификация отчёта:", spec.summary()
                        + "Нажмите «Собрать .rptdesign…», выберите файл и введите пароль базы.",
                        false);
                    setBusy(false, "Спецификация готова (" + seconds(ms)
                        + "). Теперь — «Собрать .rptdesign…».");
                } catch (Exception e) {
                    long ms = System.currentTimeMillis() - started;
                    String message = describeSpecError(e);
                    AuditLog.append("report", "spec", "ERROR", ms, model, message,
                        "ТЗ=" + tz.length() + " симв.");
                    uiAppend("Ошибка:", message, true);
                    setBusy(false, "Модель вернула не-спецификацию. Уточните ТЗ и повторите.");
                }
                return Status.OK_STATUS;
            }
        };
        job.setUser(true);
        job.schedule();
    }

    /** Сборка .rptdesign из готовой спецификации (путь к файлу + пароль). */
    private void buildReport() {
        if (busy) {
            return;
        }
        final ReportSpec spec = pendingSpec;
        if (spec == null) {
            setStatusText("Сначала получите спецификацию — кнопка «Создать отчёт…».");
            return;
        }

        FileDialog dialog = new FileDialog(getSite().getShell(), SWT.SAVE);
        dialog.setText("Куда сохранить файл отчёта");
        dialog.setFileName(spec.name() + ".rptdesign");
        dialog.setFilterExtensions(new String[] {"*.rptdesign", "*.*"});
        dialog.setFilterNames(new String[] {"Отчёт BIRT (*.rptdesign)", "Все файлы"});
        File defaultDir = defaultReportDir();
        if (defaultDir != null) {
            dialog.setFilterPath(defaultDir.getAbsolutePath());
        }
        String path = dialog.open();
        if (path == null) {
            return;
        }
        if (!path.toLowerCase().endsWith(".rptdesign")) {
            path = path + ".rptdesign";
        }
        final File target = new File(path);
        if (target.exists() && !confirmOverwrite(target)) {
            return;
        }
        if (isEditorDirty(target)) {
            setStatusText("В редакторе отчёта есть несохранённые изменения — "
                + "сохраните их или закройте редактор и повторите.");
            return;
        }

        // Пароль базы: спецификация его не содержит (правило формата v0).
        String password = null;
        ReportSpec.DataSource source = spec.singleDataSource();
        if (source != null && !source.hasPassword()) {
            DbPasswordDialog dialogPw = new DbPasswordDialog(getSite().getShell(),
                source.driver(), source.url(), source.user());
            if (dialogPw.open() != Window.OK) {
                setStatusText("Сборка отменена: пароль не введён.");
                return;
            }
            password = dialogPw.getPassword();
        }
        final String passwordOverride = password;
        final String model = BirtSettings.model();

        setBusy(true, "Собираю отчёт «" + target.getName() + "»…");
        Job job = new Job("Сборка отчёта (BIRT)") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                long started = System.currentTimeMillis();
                try {
                    ReportBuilder.build(spec, target, passwordOverride);
                    long ms = System.currentTimeMillis() - started;
                    lastBuild = new BuildState(target, spec, passwordOverride);
                    lastEdit = null;
                    BuiltReports.remember(target, spec);
                    AuditLog.append("report", "build", "OK", ms, model, null,
                        "файл=" + target.getName() + "; байт=" + target.length());
                    boolean opened = reloadEditor(target);
                    uiAppend("Отчёт собран:", "Файл: " + target.getAbsolutePath()
                        + "\nРазмер: " + target.length() + " Б"
                        + (opened
                            ? "\nОткрыт в редакторе BIRT."
                            : "\nОткройте файл командой «Открыть с помощью → Report Editor».")
                        + "\nДальше: «Просмотр…» — посмотреть, "
                        + "«Правка отчёта…» — изменить по замечанию.",
                        false);
                    if (!opened) {
                        offerOpenFolder(target);
                    }
                    setBusy(false, "Отчёт собран (" + seconds(ms) + ").");
                } catch (Throwable t) {
                    long ms = System.currentTimeMillis() - started;
                    String message = t.getMessage() == null
                        ? t.getClass().getSimpleName() : t.getMessage();
                    AuditLog.append("report", "build", "ERROR", ms, model, message,
                        "файл=" + target.getName());
                    uiAppend("Ошибка сборки:", message, true);
                    setBusy(false, "Не удалось собрать отчёт.");
                }
                return Status.OK_STATUS;
            }
        };
        job.setUser(true);
        job.schedule();
    }

    // ------------------------------------------------------------------
    // Правка отчёта по замечанию
    // ------------------------------------------------------------------

    /** Отправляет замечание модели вместе с текущей спецификацией отчёта. */
    private void editReport() {
        if (busy) {
            return;
        }
        final Target target = resolveTarget();
        if (target == null) {
            setStatusText("Не найден отчёт, собранный конструктором. Соберите отчёт "
                + "или откройте ранее собранный (спецификация хранится рядом с ним).");
            beepAndFocusInput();
            return;
        }
        if (input == null || input.isDisposed()) {
            return;
        }
        String raw = input.getText();
        if (raw == null || raw.trim().isEmpty()) {
            setStatusText("Опишите в поле ввода, что изменить "
                + "(например: «добавь колонку с курсом»), и нажмите «Правка отчёта…».");
            beepAndFocusInput();
            return;
        }
        final String remark = raw.trim();
        input.setText("");
        appendParagraph("Вы (замечание к отчёту «" + target.file.getName() + "»):", remark, false);

        final LlmClient client = LlmClient.fromSettings();
        final String model = client.getModel();
        final List<ChatMessage> apiMessages = new ArrayList<ChatMessage>();
        apiMessages.add(ChatMessage.system(
            SpecPrompts.systemInstruction() + SpecPrompts.editInstruction()));
        apiMessages.add(ChatMessage.user(
            SpecPrompts.editUserMessage(target.spec.toJson(), remark)));
        final int maxTokens = BirtSettings.maxTokens();

        setBusy(true, "Модель «" + model + "» готовит правку отчёта…");

        Job job = new Job("Правка отчёта (BIRT)") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                long started = System.currentTimeMillis();
                try {
                    String reply = client.chat(apiMessages, SPEC_TEMPERATURE, maxTokens);
                    long ms = System.currentTimeMillis() - started;
                    final ReportSpec newSpec = ReportSpec.fromLlmReply(reply);
                    final List<String> diff = SpecDiff.describe(target.spec, newSpec);
                    AuditLog.append("report", "edit-spec", "OK", ms, model, null,
                        "файл=" + target.file.getName() + "; изменений=" + diff.size());
                    setBusy(false, diff.isEmpty()
                        ? "Модель не предложила изменений."
                        : "Проверьте изменения и подтвердите применение.");
                    if (diff.isEmpty()) {
                        uiAppend("ИИ — правка отчёта:",
                            "Изменений не обнаружено: спецификация не поменялась.\n"
                            + "Уточните замечание (что именно и как изменить) и повторите.",
                            false);
                        return Status.OK_STATUS;
                    }
                    if (!confirmEdit(target.file, diff)) {
                        AuditLog.append("report", "edit", "rejected", 0, model, null,
                            "файл=" + target.file.getName());
                        uiAppend("Правка отменена:", "Изменения не применены.", false);
                        return Status.OK_STATUS;
                    }
                    applyEdit(target, newSpec, model, diff);
                } catch (Exception e) {
                    long ms = System.currentTimeMillis() - started;
                    String message = describeSpecError(e);
                    AuditLog.append("report", "edit-spec", "ERROR", ms, model, message,
                        "ТЗ=" + remark.length() + " симв.");
                    uiAppend("Ошибка:", message, true);
                    setBusy(false, "Модель вернула не-спецификацию. Уточните замечание и повторите.");
                }
                return Status.OK_STATUS;
            }
        };
        job.setUser(true);
        job.schedule();
    }

    /** Диалог подтверждения правки: список изменений и путь к файлу. */
    private boolean confirmEdit(final File file, final List<String> diff) {
        final boolean[] result = {false};
        uiExecSync(new Runnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder();
                sb.append("Применить правку отчёта?\n\nЧто изменится (по мнению модели):\n");
                int shown = Math.min(diff.size(), 12);
                for (int i = 0; i < shown; i++) {
                    sb.append("  • ").append(diff.get(i)).append('\n');
                }
                if (diff.size() > shown) {
                    sb.append("  … ещё ").append(diff.size() - shown).append('\n');
                }
                sb.append("\nФайл: ").append(file.getAbsolutePath());
                sb.append("\n\nПрежняя версия будет сохранена рядом — её можно вернуть "
                    + "кнопкой «Отменить правку».");
                MessageBox box = new MessageBox(getSite().getShell(),
                    SWT.ICON_QUESTION | SWT.OK | SWT.CANCEL);
                box.setText("Правка отчёта");
                box.setMessage(sb.toString());
                result[0] = box.open() == SWT.OK;
            }
        });
        return result[0];
    }

    /** Применяет правку: резервная копия → сборка во временный файл → замена. */
    private void applyEdit(final Target target, final ReportSpec newSpec,
            final String model, final List<String> diff) {
        final File file = target.file;
        final File dir = file.getParentFile();
        if (dir == null || !file.isFile()) {
            setStatusText("Файл отчёта не найден — соберите отчёт заново.");
            return;
        }

        // Пароль: из памяти (сборка в этой сессии) или из самого файла отчёта.
        String password = null;
        BuildState build = lastBuild;
        if (build != null && build.file.equals(file) && build.dbPassword != null) {
            password = build.dbPassword;
        }
        if (password == null) {
            password = ReportBuilder.readPassword(file);
        }
        // Ограничение BIRT 4.7: пароль с символами вне ASCII читается из
        // скрытого свойства с потерей символов («?») — в этом случае лучше
        // спросить пароль заново, чем молча применить испорченный.
        if (password != null && password.indexOf('?') >= 0) {
            password = null;
        }
        final ReportSpec.DataSource source = newSpec.singleDataSource();
        if ((password == null || password.isEmpty()) && source != null && !source.hasPassword()) {
            final String[] typed = new String[1];
            final boolean[] cancelled = {false};
            uiExecSync(new Runnable() {
                @Override
                public void run() {
                    DbPasswordDialog dialogPw = new DbPasswordDialog(getSite().getShell(),
                        source.driver(), source.url(), source.user());
                    if (dialogPw.open() == Window.OK) {
                        typed[0] = dialogPw.getPassword();
                    } else {
                        cancelled[0] = true;
                    }
                }
            });
            if (cancelled[0]) {
                setStatusText("Правка отменена: пароль не введён.");
                return;
            }
            password = typed[0];
        }
        final String passwordOverride = password;

        setBusy(true, "Применяю правку к «" + file.getName() + "»…");
        Job job = new Job("Применение правки отчёта (BIRT)") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                long started = System.currentTimeMillis();
                File backup = null;
                try {
                    if (isEditorDirty(file)) {
                        setStatusText("Правка не применена: в редакторе есть "
                            + "несохранённые изменения. Сохраните их или закройте редактор.");
                        return Status.OK_STATUS;
                    }
                    String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
                    backup = new File(dir, file.getName() + ".bak-" + stamp);
                    Files.copy(file.toPath(), backup.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);

                    // Сборка во временный файл: при ошибке исходный не портится.
                    File tmp = new File(dir, file.getName() + ".new-" + System.nanoTime());
                    try {
                        ReportBuilder.build(newSpec, tmp, passwordOverride == null ? "" : passwordOverride);
                        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    } finally {
                        if (tmp.exists() && !tmp.delete()) {
                            // временный файл останется — не критично
                        }
                    }
                } catch (Throwable t) {
                    long ms = System.currentTimeMillis() - started;
                    String message = t.getMessage() == null
                        ? t.getClass().getSimpleName() : t.getMessage();
                    AuditLog.append("report", "edit", "error", ms, model, message,
                        "файл=" + file.getName());
                    uiAppend("Ошибка применения правки:", message
                        + "\nФайл отчёта не изменён.", true);
                    setBusy(false, "Правка не применена.");
                    return Status.OK_STATUS;
                }

                long ms = System.currentTimeMillis() - started;
                lastBuild = new BuildState(file, newSpec, passwordOverride);
                lastEdit = new EditState(file, backup, target.spec);
                BuiltReports.remember(file, newSpec);
                reloadEditor(file);
                AuditLog.append("report", "edit", "applied", ms, model, null,
                    "файл=" + file.getName() + "; копия=" + backup.getName()
                        + "; изменений=" + diff.size());

                StringBuilder sb = new StringBuilder();
                sb.append("Правка применена. Изменения:\n");
                int shown = Math.min(diff.size(), 12);
                for (int i = 0; i < shown; i++) {
                    sb.append("  • ").append(diff.get(i)).append('\n');
                }
                if (diff.size() > shown) {
                    sb.append("  … ещё ").append(diff.size() - shown).append('\n');
                }
                sb.append("\nФайл: ").append(file.getAbsolutePath());
                sb.append("\nПрежняя версия: ").append(backup.getAbsolutePath());
                sb.append("\nНе подошло — нажмите «Отменить правку».");
                uiAppend("Правка отчёта:", sb.toString(), false);
                setBusy(false, "Правка применена (" + seconds(ms) + ").");
                return Status.OK_STATUS;
            }
        };
        job.setUser(true);
        job.schedule();
    }

    /** Возвращает прежнюю версию отчёта из резервной копии. */
    private void rollbackEdit() {
        if (busy) {
            return;
        }
        final EditState state = lastEdit;
        if (state == null) {
            setStatusText("Откатывать нечего: правка отчёта ещё не применялась.");
            return;
        }
        MessageBox box = new MessageBox(getSite().getShell(),
            SWT.ICON_QUESTION | SWT.OK | SWT.CANCEL);
        box.setText("Отменить правку");
        box.setMessage("Вернуть прежнюю версию отчёта?\n\nФайл: "
            + state.file.getAbsolutePath() + "\nКопия: " + state.backup.getAbsolutePath());
        if (box.open() != SWT.OK) {
            return;
        }

        final String model = BirtSettings.model();
        setBusy(true, "Возвращаю прежнюю версию отчёта…");
        Job job = new Job("Откат правки отчёта (BIRT)") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                long started = System.currentTimeMillis();
                try {
                    if (!state.backup.isFile()) {
                        throw new IOException("резервная копия не найдена: "
                            + state.backup.getAbsolutePath());
                    }
                    if (isEditorDirty(state.file)) {
                        setStatusText("Откат не выполнен: в редакторе есть "
                            + "несохранённые изменения. Сохраните их или закройте редактор.");
                        return Status.OK_STATUS;
                    }
                    Files.copy(state.backup.toPath(), state.file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                    lastBuild = new BuildState(state.file, state.prevSpec,
                        ReportBuilder.readPassword(state.file));
                    lastEdit = null;
                    BuiltReports.remember(state.file, state.prevSpec);
                    reloadEditor(state.file);
                    long ms = System.currentTimeMillis() - started;
                    AuditLog.append("report", "edit", "rolledback", ms, model, null,
                        "файл=" + state.file.getName() + "; копия=" + state.backup.getName());
                    uiAppend("Откат выполнен:",
                        "Отчёт возвращён к прежней версии.\nФайл: "
                        + state.file.getAbsolutePath(), false);
                    setBusy(false, "Откат выполнен (" + seconds(ms) + ").");
                } catch (Throwable t) {
                    long ms = System.currentTimeMillis() - started;
                    String message = t.getMessage() == null
                        ? t.getClass().getSimpleName() : t.getMessage();
                    AuditLog.append("report", "edit", "rollback-error", ms, model, message,
                        "файл=" + state.file.getName());
                    uiAppend("Ошибка отката:", message, true);
                    setBusy(false, "Не удалось вернуть прежнюю версию.");
                }
                return Status.OK_STATUS;
            }
        };
        job.setUser(true);
        job.schedule();
    }

    // ------------------------------------------------------------------
    // Предпросмотр
    // ------------------------------------------------------------------

    /** Выполняет отчёт и открывает PDF системным просмотрщиком. */
    private void previewReport() {
        if (busy) {
            return;
        }
        final Target target = resolveTarget();
        if (target == null) {
            setStatusText("Нет отчёта для просмотра: сначала соберите отчёт («Собрать .rptdesign…»).");
            return;
        }
        Map<String, String> overrides = new LinkedHashMap<String, String>();
        if (!target.spec.parameters().isEmpty()) {
            ParamsDialog dialog = new ParamsDialog(getSite().getShell(), target.spec.parameters());
            if (dialog.open() != Window.OK) {
                setStatusText("Просмотр отменён.");
                return;
            }
            overrides.putAll(dialog.values());
        }
        final Map<String, String> values = overrides;

        setBusy(true, "Готовлю предпросмотр «" + target.file.getName() + "»…");
        Job job = new Job("Предпросмотр отчёта (BIRT)") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                long started = System.currentTimeMillis();
                IReportEngine engine = null;
                try {
                    File outDir = BirtSettings.configDir().resolve("preview").toFile();
                    if (!outDir.isDirectory() && !outDir.mkdirs()) {
                        throw new IOException("не удалось создать каталог " + outDir);
                    }
                    String baseName = target.file.getName();
                    int dot = baseName.toLowerCase().lastIndexOf(".rptdesign");
                    if (dot > 0) {
                        baseName = baseName.substring(0, dot);
                    }
                    final File pdf = new File(outDir, baseName + ".pdf");
                    engine = ReportPreview.createEngine(outDir, Level.WARNING);
                    ReportPreview.render(engine, target.file, target.spec, values, "pdf", pdf);
                    long ms = System.currentTimeMillis() - started;
                    AuditLog.append("report", "preview", "OK", ms, null, null,
                        "файл=" + target.file.getName() + "; PDF " + pdf.length() + " Б");
                    final boolean[] launched = {false};
                    uiExecSync(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                launched[0] = Program.launch(pdf.getAbsolutePath());
                            } catch (Throwable ignored) {
                                launched[0] = false;
                            }
                        }
                    });
                    uiAppend("Предпросмотр отчёта:", "PDF: " + pdf.getAbsolutePath()
                        + (launched[0]
                            ? "\nОткрыт системным просмотрщиком."
                            : "\nОткрыть не удалось — откройте файл вручную."),
                        false);
                    setBusy(false, "Предпросмотр готов (" + seconds(ms) + ").");
                } catch (Throwable t) {
                    long ms = System.currentTimeMillis() - started;
                    String message = t.getMessage() == null
                        ? t.getClass().getSimpleName() : t.getMessage();
                    AuditLog.append("report", "preview", "ERROR", ms, null, message,
                        "файл=" + target.file.getName());
                    uiAppend("Ошибка предпросмотра:", message, true);
                    setBusy(false, "Не удалось выполнить отчёт.");
                } finally {
                    ReportPreview.destroy(engine);
                }
                return Status.OK_STATUS;
            }
        };
        job.setUser(true);
        job.schedule();
    }

    // ------------------------------------------------------------------
    // Схема БД (метаданные — в ТЗ)
    // ------------------------------------------------------------------

    /** Читает имена таблиц/колонок из базы и вставляет их в поле ввода. */
    private void readSchema() {
        if (busy) {
            return;
        }
        DbSchemaDialog dialog = new DbSchemaDialog(getSite().getShell(),
            BirtSettings.dbDriver(), BirtSettings.dbUrl(),
            BirtSettings.dbUser(), BirtSettings.dbPassword());
        if (dialog.open() != Window.OK) {
            return;
        }
        if (dialog.getUrl().isEmpty()) {
            setStatusText("Схема не прочитана: не задана строка подключения.");
            return;
        }
        try {
            Properties props = new Properties();
            props.putAll(BirtSettings.load());
            props.setProperty(BirtSettings.KEY_DB_DRIVER, dialog.getDriver().isEmpty()
                ? BirtSettings.DEFAULT_DB_DRIVER : dialog.getDriver());
            props.setProperty(BirtSettings.KEY_DB_URL, dialog.getUrl());
            props.setProperty(BirtSettings.KEY_DB_USER, dialog.getUser());
            props.setProperty(BirtSettings.KEY_DB_PASSWORD,
                dialog.isRemember() ? dialog.getPassword() : "");
            BirtSettings.save(props);
        } catch (IOException e) {
            uiAppend("Настройки не сохранены:", String.valueOf(e.getMessage()), true);
        }
        final String url = dialog.getUrl();
        final String user = dialog.getUser();
        final String password = dialog.getPassword();

        setBusy(true, "Читаю схему базы (только метаданные — без данных)…");
        Job job = new Job("Схема БД (BIRT)") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                long started = System.currentTimeMillis();
                try {
                    DbSchema.Schema schema = DbSchema.read(url, user, password);
                    final String text = DbSchema.toText(schema,
                        DbSchema.DEFAULT_MAX_TABLES, DbSchema.DEFAULT_MAX_COLUMNS);
                    long ms = System.currentTimeMillis() - started;
                    AuditLog.append("db", "schema", "OK", ms, null, null,
                        "таблиц=" + schema.tables().size() + "; модель не получала строк данных");
                    uiExec(new Runnable() {
                        @Override
                        public void run() {
                            if (input != null && !input.isDisposed()) {
                                input.insert(text + "\n");
                                input.setFocus();
                            }
                        }
                    });
                    uiAppend("Схема БД прочитана:", "Таблиц и представлений: "
                        + schema.tables().size()
                        + ".\nТекст вставлен в поле ввода — допишите ТЗ и нажмите «Создать отчёт…».",
                        false);
                    setBusy(false, "Схема прочитана (" + seconds(ms) + ").");
                } catch (Throwable t) {
                    long ms = System.currentTimeMillis() - started;
                    String message = t.getMessage() == null
                        ? t.getClass().getSimpleName() : t.getMessage();
                    AuditLog.append("db", "schema", "ERROR", ms, null, message, null);
                    uiAppend("Ошибка чтения схемы:", message, true);
                    setBusy(false, "Не удалось прочитать схему базы.");
                }
                return Status.OK_STATUS;
            }
        };
        job.setUser(true);
        job.schedule();
    }

    // ------------------------------------------------------------------
    // Работа с отчётами: поиск цели, редактор, помощники
    // ------------------------------------------------------------------

    /**
     * Ищет отчёт для правки/просмотра: сначала последний собранный в сессии
     * (если файл не изменился вручную), затем — открытый в редакторе отчёт
     * из кэша спецификаций (BuiltReports).
     */
    private Target resolveTarget() {
        BuildState build = lastBuild;
        if (build != null && build.isFresh()) {
            return new Target(build.file, build.spec);
        }
        File open = activeReportFile();
        if (open != null) {
            ReportSpec spec = BuiltReports.lookup(open);
            if (spec != null) {
                return new Target(open, spec);
            }
        }
        return null;
    }

    /** Файл открытого в редакторе отчёта (активный редактор — в приоритете). */
    private File activeReportFile() {
        IWorkbenchPage page = getSite().getPage();
        File file = editorFile(page.getActiveEditor());
        if (file != null) {
            return file;
        }
        for (IEditorReference ref : page.getEditorReferences()) {
            file = editorFile(ref.getEditor(false));
            if (file != null) {
                return file;
            }
        }
        return null;
    }

    private File editorFile(IEditorPart part) {
        if (part == null) {
            return null;
        }
        IEditorInput input = part.getEditorInput();
        if (input instanceof IFileEditorInput) {
            IResource resource = ((IFileEditorInput) input).getFile();
            return resource == null || resource.getLocation() == null
                ? null : resource.getLocation().toFile();
        }
        if (input instanceof IPathEditorInput) {
            return ((IPathEditorInput) input).getPath().toFile();
        }
        if (input instanceof IURIEditorInput) {
            try {
                java.net.URI uri = ((IURIEditorInput) input).getURI();
                if (uri != null && "file".equals(uri.getScheme())) {
                    return new File(uri);
                }
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private IEditorPart editorFor(File file) {
        try {
            for (IEditorReference ref : getSite().getPage().getEditorReferences()) {
                IEditorPart part = ref.getEditor(false);
                File open = editorFile(part);
                if (open != null && sameFile(open, file)) {
                    return part;
                }
            }
        } catch (Throwable ignored) {
            // рабочая область недоступна
        }
        return null;
    }

    private static boolean sameFile(File a, File b) {
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (IOException e) {
            return a.getAbsoluteFile().equals(b.getAbsoluteFile());
        }
    }

    /** Есть ли в редакторе этого файла несохранённые изменения. */
    private boolean isEditorDirty(final File file) {
        final boolean[] dirty = {false};
        uiExecSync(new Runnable() {
            @Override
            public void run() {
                IEditorPart part = editorFor(file);
                dirty[0] = part != null && part.isDirty();
            }
        });
        return dirty[0];
    }

    /**
     * Закрывает устаревший редактор файла и открывает его заново
     * (в рабочей области или как внешний файл). Возвращает true, если открыт.
     */
    private boolean reloadEditor(final File file) {
        final boolean[] opened = {false};
        uiExecSync(new Runnable() {
            @Override
            public void run() {
                try {
                    IEditorPart part = editorFor(file);
                    if (part != null) {
                        getSite().getPage().closeEditor(part, false);
                    }
                    opened[0] = openEditor(file);
                } catch (Throwable ignored) {
                    opened[0] = false;
                }
            }
        });
        return opened[0];
    }

    /** Открывает отчёт в редакторе BIRT (в рабочей области или как внешний файл). */
    private boolean openEditor(File file) {
        try {
            IFile resource = ResourcesPlugin.getWorkspace().getRoot()
                .getFileForLocation(new Path(file.getAbsolutePath()));
            if (resource != null) {
                resource.refreshLocal(IResource.DEPTH_ZERO, null);
                IDE.openEditor(getSite().getPage(), resource, true);
                return true;
            }
            IEditorInput input = new FileStoreEditorInput(
                EFS.getLocalFileSystem().fromLocalFile(file));
            IDE.openEditor(getSite().getPage(), input, REPORT_EDITOR_ID);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Сообщение об ошибке спецификации: текст плюс первые найденные проблемы. */
    private static String describeSpecError(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (e instanceof ReportSpec.SpecException) {
            List<String> problems = ((ReportSpec.SpecException) e).problems();
            if (problems != null && !problems.isEmpty()) {
                StringBuilder sb = new StringBuilder(message);
                int shown = Math.min(problems.size(), 6);
                for (int i = 0; i < shown; i++) {
                    sb.append("\n  • ").append(problems.get(i));
                }
                if (problems.size() > shown) {
                    sb.append("\n  … ещё ").append(problems.size() - shown);
                }
                message = sb.toString();
            }
        }
        return message;
    }

    /** Каталог по умолчанию для файла отчёта: первый проект рабочей области. */
    private File defaultReportDir() {
        try {
            IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
            for (IProject project : projects) {
                if (project.isAccessible() && project.getLocation() != null) {
                    return project.getLocation().toFile();
                }
            }
        } catch (Throwable ignored) {
            // рабочая область недоступна — предложим домашний каталог
        }
        return new File(System.getProperty("user.home", "."));
    }

    private boolean confirmOverwrite(File target) {
        MessageBox box = new MessageBox(getSite().getShell(),
            SWT.ICON_QUESTION | SWT.YES | SWT.NO);
        box.setText("Файл уже существует");
        box.setMessage("Перезаписать файл?\n" + target.getAbsolutePath());
        return box.open() == SWT.YES;
    }

    /** Предлагает открыть папку с собранным отчётом. */
    private void offerOpenFolder(final File file) {
        final File dir = file.getParentFile();
        if (dir == null) {
            return;
        }
        uiExecSync(new Runnable() {
            @Override
            public void run() {
                MessageBox box = new MessageBox(getSite().getShell(),
                    SWT.ICON_INFORMATION | SWT.YES | SWT.NO);
                box.setText("Отчёт собран");
                box.setMessage("Открыть папку с файлом отчёта?\n" + dir.getAbsolutePath());
                if (box.open() == SWT.YES) {
                    Program.launch(dir.getAbsolutePath());
                }
            }
        });
    }

    private void openSettings() {
        PreferenceDialog dialog = PreferencesUtil.createPreferenceDialogOn(
            getSite().getShell(), AiBirtPreferencePage.PAGE_ID,
            new String[] {AiBirtPreferencePage.PAGE_ID}, null);
        dialog.open();
        updateModelLabel();
    }

    // ------------------------------------------------------------------
    // Интерфейсные помощники
    // ------------------------------------------------------------------

    private void appendWelcome() {
        appendStyled("ИИ-конструктор отчётов BIRT", true);
        transcript.append("\nЧат с локальной моделью: задайте вопрос по BIRT "
            + "(например: «Что такое параметр отчёта?»).\n");
        transcript.append("Полный цикл: «Схема БД…» (по желанию) → опишите отчёт → "
            + "«Создать отчёт…» → «Собрать .rptdesign…» → «Просмотр…».\n");
        transcript.append("Доработка: напишите замечание и нажмите «Правка отчёта…» "
            + "(прежняя версия сохраняется, есть «Отменить правку»).\n");
        transcript.append("Подключение (адрес сервера, модель, ключ, база) — кнопка «Настройки…».\n\n");
        scrollToEnd();
    }

    private void appendStyled(String text, boolean bold) {
        int start = transcript.getCharCount();
        transcript.append(text);
        if (bold) {
            StyleRange range = new StyleRange();
            range.start = start;
            range.length = text.length();
            range.fontStyle = SWT.BOLD;
            transcript.setStyleRange(range);
        }
    }

    private void appendParagraph(String header, String body, boolean error) {
        int start = transcript.getCharCount();
        transcript.append(header);
        StyleRange range = new StyleRange();
        range.start = start;
        range.length = header.length();
        range.fontStyle = SWT.BOLD;
        if (error) {
            range.foreground = display.getSystemColor(SWT.COLOR_DARK_RED);
        }
        transcript.setStyleRange(range);
        transcript.append("\n" + body + "\n\n");
        scrollToEnd();
    }

    private void scrollToEnd() {
        int lines = transcript.getLineCount();
        if (lines > 0) {
            transcript.setTopIndex(lines - 1);
        }
    }

    private void uiAppend(final String header, final String body, final boolean error) {
        uiExec(new Runnable() {
            @Override
            public void run() {
                if (!transcript.isDisposed()) {
                    appendParagraph(header, body, error);
                }
            }
        });
    }

    private void setBusy(final boolean value, final String statusText) {
        busy = value;
        uiExec(new Runnable() {
            @Override
            public void run() {
                updateActionButtonsNow();
                if (statusLabel != null && !statusLabel.isDisposed()) {
                    statusLabel.setText(statusText);
                }
            }
        });
    }

    private void setStatusText(final String text) {
        uiExec(new Runnable() {
            @Override
            public void run() {
                if (statusLabel != null && !statusLabel.isDisposed()) {
                    statusLabel.setText(text);
                }
            }
        });
    }

    private void updateActionButtons() {
        uiExec(new Runnable() {
            @Override
            public void run() {
                updateActionButtonsNow();
            }
        });
    }

    private void updateActionButtonsNow() {
        boolean enabled = !busy;
        if (sendButton != null && !sendButton.isDisposed()) {
            sendButton.setEnabled(enabled);
        }
        if (specButton != null && !specButton.isDisposed()) {
            specButton.setEnabled(enabled);
        }
        if (editButton != null && !editButton.isDisposed()) {
            editButton.setEnabled(enabled);
        }
        if (buildButton != null && !buildButton.isDisposed()) {
            buildButton.setEnabled(enabled && pendingSpec != null);
        }
        if (previewButton != null && !previewButton.isDisposed()) {
            previewButton.setEnabled(enabled);
        }
        if (schemaButton != null && !schemaButton.isDisposed()) {
            schemaButton.setEnabled(enabled);
        }
        if (rollbackButton != null && !rollbackButton.isDisposed()) {
            rollbackButton.setEnabled(enabled && lastEdit != null);
        }
    }

    private void beepAndFocusInput() {
        if (display != null && !display.isDisposed()) {
            display.beep();
        }
        if (input != null && !input.isDisposed()) {
            input.setFocus();
        }
    }

    private void updateModelLabel() {
        if (modelLabel == null || modelLabel.isDisposed()) {
            return;
        }
        String model = BirtSettings.model();
        String url = BirtSettings.baseUrl();
        boolean hasToken = !BirtSettings.token().isEmpty();
        modelLabel.setText("Модель: " + model + "   ·   " + url
            + (hasToken ? "" : "   ·   ключ не задан"));
        modelLabel.setToolTipText("Настройки: " + BirtSettings.settingsFile());
        Composite parent = modelLabel.getParent();
        parent.layout(true);
    }

    private void uiExec(Runnable runnable) {
        if (display != null && !display.isDisposed()) {
            display.asyncExec(runnable);
        }
    }

    private void uiExecSync(Runnable runnable) {
        if (display != null && !display.isDisposed()) {
            display.syncExec(runnable);
        }
    }

    private static String seconds(long ms) {
        return String.format("%.1f с", Double.valueOf(ms / 1000.0));
    }
}
