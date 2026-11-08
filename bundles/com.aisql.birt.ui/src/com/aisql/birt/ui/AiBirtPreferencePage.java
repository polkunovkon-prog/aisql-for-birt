/*
 * AI BIRT — страница настроек плагина.
 *
 * Значения хранятся в файле ~/.ai-birt/ai-birt.properties (не в
 * preferences-хранилище Eclipse): так один и тот же файл легко править
 * вручную, копировать и сопровождать в диагностике. Кнопка «Проверить
 * соединение» обращается к серверу и показывает список моделей.
 *
 * Раздел «Защита» (ADR-0004): режим защиты от инъекций, маскирование ПДн,
 * выгрузка аудита в syslog (SIEM) с тестом, проверка целостности журнала
 * (цепочка хэшей). Поля, закреплённые политикой предприятия, заблокированы
 * и помечены.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.ui;

import com.aisql.birt.core.AuditLog;
import com.aisql.birt.core.BirtSettings;
import com.aisql.birt.core.LlmClient;
import com.aisql.birt.core.security.AuditSyslog;
import com.aisql.birt.core.security.BirtEnterprisePolicy;
import com.aisql.birt.core.security.PromptInjectionDefense;

import java.io.IOException;
import java.util.List;
import java.util.Properties;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

/** Страница «Window → Preferences → ИИ-конструктор отчётов (BIRT)». */
public class AiBirtPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    public static final String PAGE_ID = "com.aisql.birt.ui.preferencePage";

    private Text baseUrlText;
    private Text modelText;
    private Text tokenText;
    private Text temperatureText;
    private Text timeoutText;
    private Text maxTokensText;
    private Text systemPromptText;
    private Button auditCheck;
    private Label checkResultLabel;

    // Раздел «Защита»
    private Combo injectionCombo;
    private Button piiMaskingCheck;
    private Combo agentModeCombo;
    private Text agentIterationsText;
    private Combo nativeToolsCombo;
    private Button syslogCheck;
    private Text syslogHostText;
    private Text syslogPortText;
    private Label syslogStatusLabel;
    private Label auditVerifyLabel;

    public AiBirtPreferencePage() {
        setTitle("ИИ-конструктор отчётов (BIRT)");
        setDescription("Подключение к OpenAI-совместимому серверу ИИ. Файл настроек: "
            + BirtSettings.settingsFile());
    }

    @Override
    public void init(IWorkbench workbench) {
        // ничего не требуется
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite composite = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(2, false);
        composite.setLayout(layout);
        composite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        baseUrlText = addTextRow(composite, "Адрес сервера (…/v1):", 46);
        modelText = addTextRow(composite, "Модель:", 46);
        tokenText = addTextRow(composite, "Ключ доступа:", 46);
        temperatureText = addTextRow(composite, "Температура (0…2):", 10);
        timeoutText = addTextRow(composite, "Таймаут запроса, секунд:", 10);
        maxTokensText = addTextRow(composite, "Ограничение ответа (max tokens, 0 — не задавать):", 10);

        new Label(composite, SWT.NONE);
        auditCheck = new Button(composite, SWT.CHECK);
        auditCheck.setText("Вести журнал аудита (ai-birt-audit.jsonl, цепочка хэшей)");

        Label promptLabel = new Label(composite, SWT.NONE);
        promptLabel.setText("Системная инструкция чата:");
        promptLabel.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));

        systemPromptText = new Text(composite, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        GridData promptData = new GridData(SWT.FILL, SWT.FILL, true, true);
        promptData.heightHint = 96;
        systemPromptText.setLayoutData(promptData);

        createAgentSection(composite);
        createSecuritySection(composite);

        Label separator = new Label(composite, SWT.SEPARATOR | SWT.HORIZONTAL);
        GridData separatorData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        separatorData.horizontalSpan = 2;
        separator.setLayoutData(separatorData);

        Composite checkRow = new Composite(composite, SWT.NONE);
        GridData checkRowData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        checkRowData.horizontalSpan = 2;
        checkRow.setLayoutData(checkRowData);
        RowLayout checkLayout = new RowLayout(SWT.HORIZONTAL);
        checkLayout.center = true;
        checkLayout.spacing = 8;
        checkLayout.marginWidth = 0;
        checkLayout.marginHeight = 0;
        checkRow.setLayout(checkLayout);

        Button checkButton = new Button(checkRow, SWT.PUSH);
        checkButton.setText("Проверить соединение");
        checkButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                checkConnection();
            }
        });

        checkResultLabel = new Label(checkRow, SWT.NONE);
        checkResultLabel.setText("");

        loadFromSettings();
        return composite;
    }

    // ------------------------------------------------------------------
    // Раздел «Агент»
    // ------------------------------------------------------------------

    private void createAgentSection(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Агентная сборка (инструменты)");
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 8;
        gl.marginHeight = 8;
        group.setLayout(gl);
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.horizontalSpan = 2;
        group.setLayoutData(gd);

        new Label(group, SWT.NONE).setText("Режим сборки по умолчанию:");
        agentModeCombo = new Combo(group, SWT.READ_ONLY);
        agentModeCombo.add("agent — пошагово инструментами (рекомендуется)");
        agentModeCombo.add("spec — спецификация v0 одним JSON");
        agentModeCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(group, SWT.NONE).setText("Лимит итераций агента:");
        agentIterationsText = new Text(group, SWT.BORDER | SWT.SINGLE);
        agentIterationsText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        int ceiling = BirtEnterprisePolicy.maxIterations(Integer.MAX_VALUE);
        if (ceiling != Integer.MAX_VALUE) {
            agentIterationsText.setEnabled(false);
            agentIterationsText.setToolTipText("Ограничено политикой предприятия: " + ceiling);
        }

        new Label(group, SWT.NONE).setText("Протокол инструментов:");
        nativeToolsCombo = new Combo(group, SWT.READ_ONLY);
        nativeToolsCombo.add("auto — штатный tool calling, запасной текстовый");
        nativeToolsCombo.add("on — только штатный tool calling");
        nativeToolsCombo.add("off — только текстовый протокол (TOOL …)");
        nativeToolsCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    // ------------------------------------------------------------------
    // Раздел «Защита»
    // ------------------------------------------------------------------

    private void createSecuritySection(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Защита");
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 8;
        gl.marginHeight = 8;
        group.setLayout(gl);
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.horizontalSpan = 2;
        group.setLayoutData(gd);

        // Статус политики
        Label policyTitle = new Label(group, SWT.NONE);
        policyTitle.setText("Политика предприятия:");
        Label policyValue = new Label(group, SWT.WRAP);
        policyValue.setText(BirtEnterprisePolicy.describe());
        policyValue.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(group, SWT.NONE).setText("Защита от промпт-инъекций:");
        injectionCombo = new Combo(group, SWT.READ_ONLY);
        injectionCombo.add("off — только журнал");
        injectionCombo.add("flag — помечать (по умолчанию)");
        injectionCombo.add("strict — блокировать попытки угона");
        injectionCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        if (BirtEnterprisePolicy.injectionMode() != null) {
            injectionCombo.setEnabled(false);
            injectionCombo.setToolTipText("Режим закреплён политикой предприятия");
        }

        new Label(group, SWT.NONE);
        piiMaskingCheck = new Button(group, SWT.CHECK);
        piiMaskingCheck.setText("Маскировать ПДн в тексте, уходящем в модель"
            + " (email, телефоны, карты, ИНН, СНИЛС)");

        new Label(group, SWT.NONE).setText("Журнал аудита → syslog (SIEM):");
        Composite syslogRow = new Composite(group, SWT.NONE);
        GridLayout srl = new GridLayout(4, false);
        srl.marginWidth = 0;
        srl.marginHeight = 0;
        syslogRow.setLayout(srl);
        syslogRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        syslogCheck = new Button(syslogRow, SWT.CHECK);
        syslogCheck.setText("вкл");

        new Label(syslogRow, SWT.NONE).setText("хост:");
        syslogHostText = new Text(syslogRow, SWT.BORDER | SWT.SINGLE);
        GridData hostData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        hostData.widthHint = 180;
        syslogHostText.setLayoutData(hostData);

        new Label(syslogRow, SWT.NONE).setText("порт:");
        syslogPortText = new Text(syslogRow, SWT.BORDER | SWT.SINGLE);
        syslogPortText.setText(String.valueOf(AuditSyslog.DEFAULT_PORT));
        GridData portData = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        portData.widthHint = 70;
        syslogPortText.setLayoutData(portData);

        // Кнопки проверок
        new Label(group, SWT.NONE);
        Composite buttons = new Composite(group, SWT.NONE);
        RowLayout rl = new RowLayout(SWT.HORIZONTAL);
        rl.center = true;
        rl.spacing = 8;
        rl.marginWidth = 0;
        rl.marginHeight = 0;
        buttons.setLayout(rl);
        buttons.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button syslogTest = new Button(buttons, SWT.PUSH);
        syslogTest.setText("Проверить syslog");
        syslogTest.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                testSyslog();
            }
        });

        Button auditVerify = new Button(buttons, SWT.PUSH);
        auditVerify.setText("Проверить целостность журнала");
        auditVerify.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                verifyAudit();
            }
        });

        new Label(group, SWT.NONE);
        syslogStatusLabel = new Label(group, SWT.WRAP);
        syslogStatusLabel.setText("");
        syslogStatusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(group, SWT.NONE);
        auditVerifyLabel = new Label(group, SWT.WRAP);
        auditVerifyLabel.setText("");
        auditVerifyLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    private Text addTextRow(Composite composite, String labelText, int widthHint) {
        Label label = new Label(composite, SWT.NONE);
        label.setText(labelText);
        Text text = new Text(composite, SWT.BORDER | SWT.SINGLE);
        GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
        data.widthHint = widthHint * 8;
        text.setLayoutData(data);
        return text;
    }

    // ------------------------------------------------------------------
    // Загрузка / сохранение
    // ------------------------------------------------------------------

    private void loadFromSettings() {
        baseUrlText.setText(BirtSettings.baseUrl());
        modelText.setText(BirtSettings.model());
        tokenText.setText(BirtSettings.token());
        temperatureText.setText(String.valueOf(BirtSettings.temperature()));
        timeoutText.setText(String.valueOf(BirtSettings.timeoutSec()));
        maxTokensText.setText(String.valueOf(BirtSettings.maxTokens()));
        systemPromptText.setText(BirtSettings.systemPrompt());
        auditCheck.setSelection(BirtSettings.auditEnabled());
        if (BirtEnterprisePolicy.auditLocked()) {
            auditCheck.setSelection(true);
            auditCheck.setEnabled(false);
            auditCheck.setToolTipText("Журнал закреплён политикой предприятия");
        }

        agentModeCombo.select("spec".equals(BirtSettings.agentMode()) ? 1 : 0);
        agentIterationsText.setText(String.valueOf(BirtSettings.agentMaxIterations()));
        String nativeTools = BirtSettings.agentNativeTools();
        nativeToolsCombo.select("on".equals(nativeTools) ? 1
            : "off".equals(nativeTools) ? 2 : 0);

        String mode = PromptInjectionDefense.mode();
        injectionCombo.select(PromptInjectionDefense.MODE_OFF.equals(mode) ? 0
            : PromptInjectionDefense.MODE_STRICT.equals(mode) ? 2 : 1);
        piiMaskingCheck.setSelection(BirtEnterprisePolicy.piiMaskingEnabled());
        syslogCheck.setSelection(AuditSyslog.enabled());
        syslogHostText.setText(AuditSyslog.host());
        syslogPortText.setText(String.valueOf(AuditSyslog.port()));
    }

    @Override
    protected void performDefaults() {
        Properties defaults = BirtSettings.defaults();
        baseUrlText.setText(defaults.getProperty(BirtSettings.KEY_BASE_URL, ""));
        modelText.setText(defaults.getProperty(BirtSettings.KEY_MODEL, ""));
        tokenText.setText(defaults.getProperty(BirtSettings.KEY_TOKEN, ""));
        temperatureText.setText(defaults.getProperty(BirtSettings.KEY_TEMPERATURE, ""));
        timeoutText.setText(defaults.getProperty(BirtSettings.KEY_TIMEOUT, ""));
        maxTokensText.setText(defaults.getProperty(BirtSettings.KEY_MAX_TOKENS, ""));
        systemPromptText.setText(defaults.getProperty(BirtSettings.KEY_SYSTEM_PROMPT, ""));
        auditCheck.setSelection(Boolean.parseBoolean(defaults.getProperty(BirtSettings.KEY_AUDIT_ENABLED, "true")));
        super.performDefaults();
    }

    @Override
    public boolean performOk() {
        String baseUrl = baseUrlText.getText().trim();
        if (baseUrl.isEmpty()) {
            setErrorMessage("Укажите адрес сервера (например, http://host:8888/v1).");
            return false;
        }
        String model = modelText.getText().trim();
        if (model.isEmpty()) {
            setErrorMessage("Укажите идентификатор модели.");
            return false;
        }
        double temperature;
        try {
            temperature = Double.parseDouble(temperatureText.getText().trim());
        } catch (NumberFormatException e) {
            setErrorMessage("Температура должна быть числом (например, 0.3).");
            return false;
        }
        if (temperature < 0 || temperature > 2) {
            setErrorMessage("Температура должна быть в диапазоне 0…2.");
            return false;
        }
        int timeout;
        try {
            timeout = Integer.parseInt(timeoutText.getText().trim());
        } catch (NumberFormatException e) {
            setErrorMessage("Таймаут должен быть целым числом секунд.");
            return false;
        }
        if (timeout <= 0) {
            setErrorMessage("Таймаут должен быть больше нуля.");
            return false;
        }
        int maxTokens;
        try {
            maxTokens = Integer.parseInt(maxTokensText.getText().trim());
        } catch (NumberFormatException e) {
            setErrorMessage("Ограничение ответа должно быть целым числом (0 — не задавать).");
            return false;
        }
        if (maxTokens < 0) {
            setErrorMessage("Ограничение ответа не может быть отрицательным.");
            return false;
        }
        int iterations;
        try {
            iterations = Integer.parseInt(agentIterationsText.getText().trim());
        } catch (NumberFormatException e) {
            setErrorMessage("Лимит итераций должен быть целым числом.");
            return false;
        }
        if (iterations <= 0) {
            setErrorMessage("Лимит итераций должен быть больше нуля.");
            return false;
        }
        int syslogPort;
        try {
            syslogPort = Integer.parseInt(syslogPortText.getText().trim());
        } catch (NumberFormatException e) {
            setErrorMessage("Порт syslog должен быть целым числом.");
            return false;
        }
        if (syslogPort <= 0 || syslogPort > 65535) {
            setErrorMessage("Порт syslog должен быть в диапазоне 1…65535.");
            return false;
        }

        // Сохраняем поверх текущего файла: незнакомые ключи не теряются.
        Properties properties = new Properties();
        Properties current = BirtSettings.load();
        for (String name : current.stringPropertyNames()) {
            properties.setProperty(name, current.getProperty(name, ""));
        }
        properties.setProperty(BirtSettings.KEY_BASE_URL, baseUrl);
        properties.setProperty(BirtSettings.KEY_MODEL, model);
        properties.setProperty(BirtSettings.KEY_TOKEN, tokenText.getText().trim());
        properties.setProperty(BirtSettings.KEY_TEMPERATURE, String.valueOf(temperature));
        properties.setProperty(BirtSettings.KEY_TIMEOUT, String.valueOf(timeout));
        properties.setProperty(BirtSettings.KEY_MAX_TOKENS, String.valueOf(maxTokens));
        properties.setProperty(BirtSettings.KEY_SYSTEM_PROMPT, systemPromptText.getText().trim());
        properties.setProperty(BirtSettings.KEY_AUDIT_ENABLED,
            BirtEnterprisePolicy.auditLocked()
                ? "true" : String.valueOf(auditCheck.getSelection()));
        properties.setProperty(BirtSettings.KEY_AGENT_MODE,
            agentModeCombo.getSelectionIndex() == 1 ? "spec" : "agent");
        properties.setProperty(BirtSettings.KEY_AGENT_MAX_ITERATIONS, String.valueOf(iterations));
        properties.setProperty(BirtSettings.KEY_AGENT_NATIVE_TOOLS,
            nativeToolsCombo.getSelectionIndex() == 1 ? "on"
                : nativeToolsCombo.getSelectionIndex() == 2 ? "off" : "auto");
        if (injectionCombo.getEnabled()) {
            properties.setProperty(BirtSettings.KEY_INJECTION,
                injectionCombo.getSelectionIndex() == 0 ? PromptInjectionDefense.MODE_OFF
                    : injectionCombo.getSelectionIndex() == 2
                        ? PromptInjectionDefense.MODE_STRICT
                        : PromptInjectionDefense.MODE_FLAG);
        }
        properties.setProperty(BirtSettings.KEY_PII_MASKING,
            String.valueOf(piiMaskingCheck.getSelection()));
        properties.setProperty(BirtSettings.KEY_SYSLOG_ENABLED,
            String.valueOf(syslogCheck.getSelection()));
        properties.setProperty(BirtSettings.KEY_SYSLOG_HOST, syslogHostText.getText().trim());
        properties.setProperty(BirtSettings.KEY_SYSLOG_PORT, String.valueOf(syslogPort));

        try {
            BirtSettings.save(properties);
        } catch (IOException e) {
            setErrorMessage("Не удалось сохранить настройки: " + e.getMessage());
            return false;
        }
        setErrorMessage(null);
        return super.performOk();
    }

    // ------------------------------------------------------------------
    // Проверка соединения
    // ------------------------------------------------------------------

    private void checkConnection() {
        final String baseUrl = baseUrlText.getText().trim();
        if (baseUrl.isEmpty()) {
            setCheckResult("Сначала укажите адрес сервера.", true);
            return;
        }
        final String model = modelText.getText().trim();
        final String token = tokenText.getText().trim();
        final int timeout;
        try {
            timeout = Integer.parseInt(timeoutText.getText().trim());
        } catch (NumberFormatException e) {
            setCheckResult("Таймаут должен быть целым числом секунд.", true);
            return;
        }

        setCheckResult("Проверка связи…", false);
        Job job = new Job("Проверка соединения с ИИ-сервером") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                final LlmClient client = new LlmClient(baseUrl, model, token, timeout);
                try {
                    List<String> models = client.listModels();
                    final String text;
                    if (model.isEmpty()) {
                        text = "Связь есть. Моделей на сервере: " + models.size() + ".";
                    } else if (models.contains(model)) {
                        text = "Связь есть. Моделей на сервере: " + models.size()
                            + "; выбранная модель найдена.";
                    } else {
                        text = "Связь есть. Моделей на сервере: " + models.size()
                            + "; выбранной модели нет в списке (сервер может показывать не все модели).";
                    }
                    setCheckResult(text, false);
                } catch (Exception e) {
                    String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    setCheckResult(message, true);
                }
                return Status.OK_STATUS;
            }
        };
        job.setUser(true);
        job.schedule();
    }

    // ------------------------------------------------------------------
    // Проверки раздела «Защита»
    // ------------------------------------------------------------------

    /** Отправляет тестовое сообщение syslog (использует введённые значения). */
    private void testSyslog() {
        final String host = syslogHostText.getText().trim();
        if (host.isEmpty()) {
            setSyslogStatus("Укажите хост syslog (например, localhost или адрес SIEM).", true);
            return;
        }
        final int port;
        try {
            port = Integer.parseInt(syslogPortText.getText().trim());
        } catch (NumberFormatException e) {
            setSyslogStatus("Порт syslog должен быть целым числом.", true);
            return;
        }
        setSyslogStatus("Отправка тестового сообщения…", false);
        Job job = new Job("Тест syslog") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                // отправляем напрямую по введённым значениям
                try {
                    java.net.DatagramSocket socket = new java.net.DatagramSocket();
                    try {
                        String message = AuditSyslog.format(6, "AIBIRT",
                            "[ai-birt@1 test=\"true\"]",
                            "тестовое сообщение ai-birt (проверка настроек)");
                        byte[] bytes = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        java.net.InetAddress address = java.net.InetAddress.getByName(host);
                        socket.send(new java.net.DatagramPacket(bytes, bytes.length, address, port));
                    } finally {
                        socket.close();
                    }
                    AuditLog.append("syslog", "test", "OK", -1, null, null,
                        "host=" + host + ":" + port);
                    setSyslogStatus("Сообщение отправлено на " + host + ":" + port
                        + ". Проверьте приём в SIEM/журнале сервера.", false);
                } catch (final Exception e) {
                    setSyslogStatus("Не удалось отправить: " + e.getMessage(), true);
                }
                return Status.OK_STATUS;
            }
        };
        job.setUser(true);
        job.schedule();
    }

    /** Проверяет целостность журнала аудита (цепочка хэшей). */
    private void verifyAudit() {
        Job job = new Job("Проверка журнала аудита") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                final AuditLog.VerifyResult r = AuditLog.verifyChain();
                AuditLog.append("audit", "verify", r.ok ? "OK" : "ERROR", -1, null, null,
                    r.describe());
                setAuditVerify(r.describe(), !r.ok);
                return Status.OK_STATUS;
            }
        };
        job.setUser(true);
        job.schedule();
    }

    private void setCheckResult(final String text, final boolean error) {
        setLabel(checkResultLabel, text, error);
    }

    private void setSyslogStatus(final String text, final boolean error) {
        setLabel(syslogStatusLabel, text, error);
    }

    private void setAuditVerify(final String text, final boolean error) {
        setLabel(auditVerifyLabel, text, error);
    }

    private static void setLabel(final Label label, final String text, final boolean error) {
        if (label == null || label.isDisposed()) {
            return;
        }
        label.getDisplay().asyncExec(new Runnable() {
            @Override
            public void run() {
                if (label.isDisposed()) {
                    return;
                }
                label.setText(text);
                label.setForeground(error
                    ? label.getDisplay().getSystemColor(SWT.COLOR_DARK_RED)
                    : null);
                Composite parent = label.getParent();
                parent.layout(true);
            }
        });
    }
}
