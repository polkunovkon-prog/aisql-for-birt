/*
 * AI BIRT — панель чата «ИИ-конструктор отчётов».
 *
 * Этап M1: чат с локальной моделью через OpenAI-совместимый сервер.
 * Запрос выполняется в фоновом Job, интерфейс не блокируется; ответ
 * дописывается в панель по мере готовности. Настройки берутся из файла
 * ~/.ai-birt/ai-birt.properties (см. страницу настроек).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.ui;

import com.aisql.birt.core.AuditLog;
import com.aisql.birt.core.BirtSettings;
import com.aisql.birt.core.ChatHistory;
import com.aisql.birt.core.ChatMessage;
import com.aisql.birt.core.LlmClient;
import com.aisql.birt.core.report.ReportBuilder;
import com.aisql.birt.core.spec.ReportSpec;
import com.aisql.birt.core.spec.SpecPrompts;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.ViewPart;

/** Панель чата с локальной ИИ-моделью. */
public class ChatView extends ViewPart {

    public static final String VIEW_ID = "com.aisql.birt.ui.chatView";

    /** Пониженная температура для генерации спецификации: нужен строгий JSON. */
    private static final double SPEC_TEMPERATURE = 0.2d;

    private Display display;
    private StyledText transcript;
    private Text input;
    private Button sendButton;
    private Button specButton;
    private Button buildButton;
    private Label modelLabel;
    private Label statusLabel;

    private final ChatHistory history = new ChatHistory(ChatHistory.DEFAULT_MAX_CHARS);
    private volatile boolean busy;
    private volatile ReportSpec pendingSpec;

    @Override
    public void createPartControl(Composite parent) {
        display = parent.getDisplay();
        parent.setLayout(new GridLayout(1, false));

        createHeader(parent);
        createTranscript(parent);
        createInputRow(parent);

        statusLabel = new Label(parent, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));

        appendWelcome();
        updateModelLabel();
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
        settingsButton.setToolTipText("Адрес сервера, модель, ключ доступа");
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

        specButton = new Button(header, SWT.PUSH);
        specButton.setText("Создать отчёт…");
        specButton.setToolTipText("Отправить описание отчёта из поля ввода модели: "
            + "в ответ она вернёт спецификацию отчёта");
        specButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                generateSpec();
            }
        });

        buildButton = new Button(header, SWT.PUSH);
        buildButton.setText("Собрать .rptdesign…");
        buildButton.setToolTipText("Собрать файл отчёта из готовой спецификации");
        buildButton.setEnabled(false);
        buildButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                buildReport();
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
        input.setToolTipText("Введите вопрос; отправка — Ctrl+Enter");
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
            if (display != null && !display.isDisposed()) {
                display.beep();
                input.setFocus();
            }
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

        // Пароль базы: спецификация его не содержит (правило формата v0).
        String password = null;
        ReportSpec.DataSource source = spec.singleDataSource();
        if (source != null && !source.hasPassword()) {
            DbPasswordDialog dialogPw = new DbPasswordDialog(getSite().getShell(),
                source.driver(), source.url(), source.user());
            if (dialogPw.open() != org.eclipse.jface.window.Window.OK) {
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
                    AuditLog.append("report", "build", "OK", ms, model, null,
                        "файл=" + target.getName() + "; байт=" + target.length());
                    boolean opened = openInEditor(target);
                    uiAppend("Отчёт собран:", "Файл: " + target.getAbsolutePath()
                        + "\nРазмер: " + target.length() + " Б"
                        + (opened
                            ? "\nОткрыт в редакторе BIRT."
                            : "\nФайл вне рабочей области Eclipse: откройте его командой «Открыть с помощью → Report Editor»."),
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

    /** Открывает отчёт в редакторе BIRT, если файл лежит в рабочей области. */
    private boolean openInEditor(File file) {
        final IFile resource = ResourcesPlugin.getWorkspace().getRoot()
            .getFileForLocation(new Path(file.getAbsolutePath()));
        if (resource == null) {
            return false;
        }
        final Throwable[] failure = new Throwable[1];
        uiExecSync(new Runnable() {
            @Override
            public void run() {
                try {
                    resource.refreshLocal(IResource.DEPTH_ZERO, null);
                    IDE.openEditor(getSite().getPage(), resource, true);
                } catch (Throwable t) {
                    failure[0] = t;
                }
            }
        });
        return failure[0] == null;
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
        transcript.append("Создание отчёта: опишите отчёт во вводе и нажмите «Создать отчёт…» — "
            + "модель вернёт спецификацию; затем «Собрать .rptdesign…» соберёт файл отчёта.\n");
        transcript.append("Подключение (адрес сервера, модель, ключ) — кнопка «Настройки…».\n\n");
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
                if (sendButton != null && !sendButton.isDisposed()) {
                    sendButton.setEnabled(!value);
                }
                if (specButton != null && !specButton.isDisposed()) {
                    specButton.setEnabled(!value);
                }
                if (buildButton != null && !buildButton.isDisposed()) {
                    buildButton.setEnabled(!value && pendingSpec != null);
                }
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
