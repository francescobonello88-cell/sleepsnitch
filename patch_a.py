p = 'app/src/main/java/mt/sleepsnitch/SleepRecordService.java'
s = open(p, encoding='utf-8').read()

# 1) extra live-state fields
old = "    private final List<JSONObject> fresh = new ArrayList<>();"
assert old in s
s = s.replace(old, old + """
    private volatile float rmsRaw = 0f;
    private volatile float floor = 0.0025f;
    private volatile long lastAudioAt = 0;
    private volatile int reopens = 0;
    private volatile long blocksSeen = 0;
    private volatile int source = MediaRecorder.AudioSource.MIC;""", 1)

# 2) factor out record construction so the watchdog can reopen with another source
old2 = """    private void startRecording() {
        error = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufBytes = Math.max(min, RATE / 4);
            if (Build.VERSION.SDK_INT >= 23) {
                AudioFormat fmt = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build();
                record = new AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.MIC)
                        .setAudioFormat(fmt)
                        .setBufferSizeInBytes(bufBytes * 2)
                        .build();
            } else {
                record = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes * 2);
            }"""
new2 = """    private AudioRecord buildRecord(int src) {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufBytes = Math.max(min, RATE / 4);
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
        return new AudioRecord.Builder()
                .setAudioSource(src)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(bufBytes * 2)
                .build();
    }

    private void startRecording() {
        error = null;
        try {
            source = MediaRecorder.AudioSource.MIC;
            record = buildRecord(source);"""
assert old2 in s
s = s.replace(old2, new2, 1)

open(p, 'w', encoding='utf-8').write(s)
print('service part A written')
