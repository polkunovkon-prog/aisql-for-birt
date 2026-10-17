/*
 * AI BIRT — страница настроек плагина.
 *
 * Значения хранятся в файле ~/.ai-birt/ai-birt.properties (не в
 * preferences-хранилище Eclipse): так один и тот же файл легко править
 * вручную, копировать и сопровождать в диагностике. Кнопка «Проверить
 * соединение» обращается к серверу и показывает список моделей.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.ui;

import com.aisql.birt.core.BirtSettings;
import com.aisql.birt.core.LlmClient;

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
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
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
        auditCheck.setText("Вести журнал аудита (ai-birt-audit.jsonl)");

        Label promptLabel = new Label(composite, SWT.NONE);
        promptLabel.setText("Системная инструкция чата:");
        promptLabel.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));

        systemPromptText = new Text(composite, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        GridData promptData = new GridData(SWT.FILL, SWT.FILL, true, true);
        promptData.heightHint = 96;
        systemPromptText.setLayoutData(promptData);

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
        properties.setProperty(BirtSettings.KEY_AUDIT_ENABLED, String.valueOf(auditCheck.getSelection()));

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

    private void setCheckResult(final String text, final boolean error) {
        if (checkResultLabel == null || checkResultLabel.isDisposed()) {
            return;
        }
        checkResultLabel.getDisplay().asyncExec(new Runnable() {
            @Override
            public void run() {
                if (checkResultLabel.isDisposed()) {
                    return;
                }
                checkResultLabel.setText(text);
                checkResultLabel.setForeground(error
                    ? checkResultLabel.getDisplay().getSystemColor(SWT.COLOR_DARK_RED)
                    : null);
                Composite parent = checkResultLabel.getParent();
                parent.layout(true);
            }
        });
    }
}
