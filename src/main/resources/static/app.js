// WebSocket connection manager with auto-reconnect
const App = {
    ws: null,
    token: null,
    connected: false,
    retryCount: 0,
    maxRetryDelay: 15000,
    fresh: true,

    connect(token) {
        this.token = token;
        this.fresh = true;
        this.retryCount = 0;
        this._doConnect();
    },

    _doConnect() {
        const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
        const url = proto + '//' + location.host + '/ws';

        this._setStatus('connecting', '● Connecting...');
        this.ws = new WebSocket(url);

        this.ws.onopen = () => {
            this.retryCount = 0;
            this.ws.send(JSON.stringify({ type: 'auth', token: this.token }));
        };

        this.ws.onmessage = (e) => {
            try {
                const msg = JSON.parse(e.data);
                this._dispatch(msg);
            } catch (err) {
                console.error('Parse error:', err);
            }
        };

        this.ws.onclose = (e) => {
            this.connected = false;
            if (e.code !== 1000) {
                this._setStatus('disconnected', '● Disconnected');
                this._scheduleReconnect();
            }
        };

        this.ws.onerror = () => {
            // onclose will fire after this
        };
    },

    _dispatch(msg) {
        switch (msg.type) {
            case 'auth':
                if (msg.ok) {
                    this.connected = true;
                    this._setStatus('connected', '● Connected');
                    document.getElementById('login-screen').style.display = 'none';
                    document.getElementById('app-screen').style.display = 'block';
                    Terminal.init();
                    FileManager.init();
                    Terminal.sendInit(this.fresh);
                } else {
                    document.getElementById('login-error').textContent =
                        msg.error || 'Invalid token';
                }
                break;
            case 'term.output':
                Terminal.write(msg.data);
                break;
            case 'term.replay':
                Terminal.replay(msg.data);
                break;
            case 'fs.list':
                FileManager.onList(msg);
                break;
            case 'fs.read':
                FileManager.onRead(msg);
                break;
            case 'fs.result':
                FileManager.onResult(msg);
                break;
            case 'error':
                console.error('Server error:', msg.message);
                break;
        }
    },

    _scheduleReconnect() {
        if (this.fresh) this.fresh = false; // subsequent connects are reconnects
        const base = Math.min(1000 * Math.pow(2, this.retryCount), this.maxRetryDelay);
        const jitter = base * 0.2 * Math.random();
        const delay = base + jitter;
        this.retryCount++;
        console.log('Reconnecting in ' + Math.round(delay) + 'ms (attempt ' + this.retryCount + ')');
        setTimeout(() => this._doConnect(), delay);
    },

    _setStatus(state, text) {
        const el = document.getElementById('connection-status');
        if (el) {
            el.className = 'status-' + state;
            el.textContent = text;
        }
    },

    send(obj) {
        if (this.ws && this.ws.readyState === WebSocket.OPEN) {
            this.ws.send(JSON.stringify(obj));
        }
    },

    disconnect() {
        this.retryCount = 999; // prevent reconnect
        if (this.ws) this.ws.close();
        this.connected = false;
        document.getElementById('login-screen').style.display = 'flex';
        document.getElementById('app-screen').style.display = 'none';
    }
};

// Login UI
document.getElementById('connect-btn').addEventListener('click', () => {
    const token = document.getElementById('token-input').value.trim();
    if (!token) return;
    document.getElementById('login-error').textContent = '';
    App.connect(token);
});
document.getElementById('token-input').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') document.getElementById('connect-btn').click();
});

// Toolbar
document.getElementById('disconnect-btn').addEventListener('click', () => App.disconnect());
document.getElementById('toggle-files').addEventListener('click', () => {
    document.getElementById('sidebar').classList.toggle('open');
});
