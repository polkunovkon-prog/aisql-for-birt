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

import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
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
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.part.ViewPart;

/** Панель чата с локальной ИИ-моделью. */
public class ChatView extends ViewPart {

    public static final String VIEW_ID = "com.aisql.birt.ui.chatView";

    private Display display;
    private StyledText transcript;
    private Text input;
    private Button sendButton;
    private Label modelLabel;
    private Label statusLabel;

    private final ChatHistory history = new ChatHistory(ChatHistory.DEFAULT_MAX_CHARS);
    private volatile boolean busy;

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
        transcript.append("\nЭтап M1 — чат с локальной моделью.\n");
        transcript.append("Задайте вопрос по BIRT (например: «Что такое параметр отчёта?»).\n");
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

    private static String seconds(long ms) {
        return String.format("%.1f с", Double.valueOf(ms / 1000.0));
    }
}
