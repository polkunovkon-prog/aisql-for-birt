/*
 * AI BIRT — диалог значений параметров отчёта (предпросмотр).
 *
 * Панель чата перед просмотром показывает параметры отчёта со значениями
 * по умолчанию; пользователь может их поправить и повторить просмотр.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.birt.ui;

import com.aisql.birt.core.spec.ReportSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/** Диалог «параметры отчёта» для предпросмотра. */
public class ParamsDialog extends Dialog {

    private final List<ReportSpec.Parameter> parameters;
    private final Map<String, Text> fields = new LinkedHashMap<String, Text>();
    private final Map<String, String> values = new LinkedHashMap<String, String>();

    public ParamsDialog(Shell parentShell, List<ReportSpec.Parameter> parameters) {
        super(parentShell);
        this.parameters = parameters;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Параметры отчёта (предпросмотр)");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        Composite grid = new Composite(area, SWT.NONE);
        grid.setLayout(new GridLayout(2, false));
        grid.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        for (ReportSpec.Parameter p : parameters) {
            Label label = new Label(grid, SWT.NONE);
            label.setText((p.prompt() == null || p.prompt().isEmpty() ? p.name() : p.prompt()) + ":");
            Text text = new Text(grid, SWT.BORDER);
            GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
            data.widthHint = 260;
            text.setLayoutData(data);
            text.setText(p.def() == null ? "" : p.def());
            text.setToolTipText("Параметр " + p.name() + " (" + p.type() + ")");
            fields.put(p.name(), text);
        }

        Label hint = new Label(grid, SWT.WRAP);
        hint.setText("Значения используются только для предпросмотра; "
            + "в файл отчёта они не записываются.");
        GridData hintData = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
        hintData.widthHint = 420;
        hint.setLayoutData(hintData);

        return area;
    }

    @Override
    protected void okPressed() {
        values.clear();
        for (Map.Entry<String, Text> e : fields.entrySet()) {
            values.put(e.getKey(), e.getValue().getText());
        }
        super.okPressed();
    }

    /** Введённые значения параметров (после закрытия кнопкой «ОК»). */
    public Map<String, String> values() {
        return values;
    }
}
