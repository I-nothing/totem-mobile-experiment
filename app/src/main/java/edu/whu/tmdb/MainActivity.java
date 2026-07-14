package edu.whu.tmdb;

import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Html;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import edu.whu.tmdb.R;

public class MainActivity extends AppCompatActivity {

    private EditText etCmd;
    private LinearLayout resultContainer;
    private ScrollView scrollView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etCmd = findViewById(R.id.etCmd);
        resultContainer = findViewById(R.id.resultContainer);
        scrollView = findViewById(R.id.scrollView);

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        scrollView.setFillViewport(true);

        Button btExecute = findViewById(R.id.btExecute);
        Button btClear = findViewById(R.id.btClear);

        btExecute.setOnClickListener(v -> {
            String sqlCommand = etCmd.getText().toString().trim();
            if (sqlCommand.isEmpty()) {
                Toast.makeText(this, "请输入SQL命令", Toast.LENGTH_SHORT).show();
                return;
            }

            String[] rawResults = Main.execute_UI(sqlCommand);

            for (String rawResult : rawResults) {
                TextView tv = new TextView(this);
                tv.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

                String htmlResult = "<pre><tt>" +
                        rawResult.replace(" ", "&nbsp;")
                                .replace("\n", "<br>") +
                        "</tt></pre>";
                tv.setText(Html.fromHtml(htmlResult, Html.FROM_HTML_MODE_LEGACY));

                tv.setTextSize(14);
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setTextColor(0xFF1A1A1A);

                tv.setPadding(4, 4, 4, 4);
                resultContainer.addView(tv);
            }

            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
            etCmd.setText("");
        });

        btClear.setOnClickListener(v -> resultContainer.removeAllViews());
    }
}
