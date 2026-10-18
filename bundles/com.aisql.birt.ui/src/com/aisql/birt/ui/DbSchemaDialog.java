/*
 * AI BIRT — диалог подключения к базе для чтения схемы (метаданных).
 *
 * В модель уходят только имена таблиц и колонок — без строк данных
 * (см. docs/SECURITY.md). Адрес, пользователь и (по желанию) пароль
 * сохраняются в настройках плагина (~/.ai-birt/ai-birt.properties),
 * чтобы при следующем чтении схемы их не вводить заново.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.ui;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/** Диалог «схема БД»: подключение для чтения имён таблиц и колонок. */
public class DbSchemaDialog extends Dialog {

    private final String driver;
    private final String url;
    private final String user;
    private final String password;

    private Text driverText;
    private Text urlText;
    private Text userText;
    private Text passwordText;
    private Button rememberCheck;

    private String resultUrl = "";
    private String resultUser = "";
    private String resultPassword = "";
    private String resultDriver = "";
    private boolean remember;

    public DbSchemaDialog(Shell parentShell, String driver, String url,
            String user, String password) {
        super(parentShell);
        this.driver = driver == null ? "" : driver;
        this.url = url == null ? "" : url;
        this.user = user == null ? "" : user;
        this.password = password == null ? "" : password;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Схема базы данных");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        Composite grid = new Composite(area, SWT.NONE);
        grid.setLayout(new GridLayout(2, false));
        grid.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        addLabel(grid, "Драйвер JDBC:");
        driverText = addText(grid, driver);

        addLabel(grid, "Строка подключения:");
        urlText = addText(grid, url);

        addLabel(grid, "Пользователь:");
        userText = addText(grid, user);

        addLabel(grid, "Пароль:");
        passwordText = new Text(grid, SWT.BORDER | SWT.PASSWORD);
        GridData pwData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        pwData.widthHint = 280;
        passwordText.setLayoutData(pwData);
        passwordText.setText(password);

        rememberCheck = new Button(grid, SWT.CHECK);
        rememberCheck.setText("Сохранить пароль в настройках плагина");
        rememberCheck.setSelection(!password.isEmpty());
        rememberCheck.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        rememberCheck.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                // сохранение пароля — осознанный выбор пользователя
            }
        });

        Label hint = new Label(grid, SWT.WRAP);
        hint.setText("В ИИ-модель уйдут только имена таблиц и колонок — без данных.\n"
            + "Пароль используется для подключения и в модель не передаётся.");
        GridData hintData = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
        hintData.widthHint = 460;
        hint.setLayoutData(hintData);

        return area;
    }

    @Override
    protected void okPressed() {
        resultDriver = driverText == null ? "" : driverText.getText().trim();
        resultUrl = urlText == null ? "" : urlText.getText().trim();
        resultUser = userText == null ? "" : userText.getText().trim();
        resultPassword = passwordText == null ? "" : passwordText.getText();
        remember = rememberCheck != null && rememberCheck.getSelection();
        super.okPressed();
    }

    private static void addLabel(Composite grid, String text) {
        Label label = new Label(grid, SWT.NONE);
        label.setText(text);
    }

    private static Text addText(Composite grid, String value) {
        Text text = new Text(grid, SWT.BORDER);
        GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
        data.widthHint = 280;
        text.setLayoutData(data);
        text.setText(value);
        return text;
    }

    public String getUrl() { return resultUrl; }
    public String getUser() { return resultUser; }
    public String getPassword() { return resultPassword; }
    public String getDriver() { return resultDriver; }
    public boolean isRemember() { return remember; }
}
