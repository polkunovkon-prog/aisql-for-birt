/*
 * AI BIRT — диалог ввода пароля БД для сборки отчёта.
 *
 * Спецификация отчёта пароль не содержит (правило формата v0), поэтому
 * перед сборкой .rptdesign его спрашивают отдельно. Пользователь и
 * строка подключения показываются только для контроля (взять их можно
 * из спецификации; чтобы изменить — поправьте ТЗ и запросите её снова).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.ui;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/** Диалог «пароль к базе данных» при сборке отчёта. */
public class DbPasswordDialog extends Dialog {

    private final String driver;
    private final String url;
    private final String user;

    private Text passwordText;
    private String password = "";

    public DbPasswordDialog(Shell parentShell, String driver, String url, String user) {
        super(parentShell);
        this.driver = driver == null ? "" : driver;
        this.url = url == null ? "" : url;
        this.user = user == null ? "" : user;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Пароль базы данных");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        Composite grid = new Composite(area, SWT.NONE);
        grid.setLayout(new GridLayout(2, false));
        grid.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        addValue(grid, "Драйвер:", driver);
        addValue(grid, "Строка подключения:", url);
        addValue(grid, "Пользователь:", user);

        Label label = new Label(grid, SWT.NONE);
        label.setText("Пароль:");
        passwordText = new Text(grid, SWT.BORDER | SWT.PASSWORD);
        GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
        data.widthHint = 280;
        passwordText.setLayoutData(data);

        Label hint = new Label(grid, SWT.WRAP);
        hint.setText("Пароль будет сохранён внутри файла отчёта (в скрытом виде), "
            + "чтобы предпросмотр и запуск работали без повторного ввода.");
        GridData hintData = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
        hintData.widthHint = 420;
        hint.setLayoutData(hintData);

        return area;
    }

    @Override
    protected void okPressed() {
        password = passwordText == null ? "" : passwordText.getText();
        super.okPressed();
    }

    private static void addValue(Composite grid, String name, String value) {
        Label label = new Label(grid, SWT.NONE);
        label.setText(name);
        Label text = new Label(grid, SWT.NONE);
        text.setText(value);
        text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    /** Введённый пароль (после закрытия диалога кнопкой «ОК»). */
    public String getPassword() {
        return password;
    }
}
