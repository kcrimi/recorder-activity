package com.example.kevin.recordedactivity;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

public class MainActivity extends RecordedActivity {

    private int count = 0;
    private TextView mCountText;
    private Button mButton;
    private EditText mEditText;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mCountText = (TextView)findViewById(R.id.count);
        mButton = (Button)findViewById(R.id.button);
        mEditText = (EditText)findViewById(R.id.edit);

        mButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                count++;
                mCountText.setText("Count is "+count);
                int color = getResources().getColor(count %2==0? R.color.colorAccent :R.color.colorPrimary);
                mButton.setBackgroundColor(color);
            }
        });

    }
}
