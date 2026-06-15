const App = {
    ws: null,
    token: null,
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
            this.send({ type: 'auth', token: this.token });
        };

        this.ws.onmessage = (e) => {
            try { this._dispatch(JSON.parse(e.data)); }
            catch (err) { console.error(err); }
        };

        this.ws.onclose = (e) => {
            if (e.code !== 1000) {
                this._setStatus('disconnected', '● Disconnected');
                this._scheduleReconnect();
            }
        };
    },

    _dispatch(msg) {
        switch (msg.type) {
            case 'auth':
                if (msg.ok) {
                    this._setStatus('connected', '● Connected');
                    document.getElementById('login-screen').style.display = 'none';
                    document.getElementById('app-screen').style.display = 'grid';
                    Output.init();
                    FileManager.init();
                    Output.sendInit();
                } else {
                    document.getElementById('login-error').textContent = msg.error || 'Invalid token';
                }
                break;
            case 'term.init': break;
            case 'term.output': Output.write(msg.data); break;
            case 'fs.list': FileManager.onList(msg); break;
            case 'fs.read': FileManager.onRead(msg); break;
            case 'fs.result': FileManager.onResult(msg); break;
            case 'error':
                console.error(msg.message);
                Output.write('\n[Error: ' + msg.message + ']\n');
                break;
        }
    },

    _scheduleReconnect() {
        this.fresh = false;
        const base = Math.min(1000 * Math.pow(2, this.retryCount), this.maxRetryDelay);
        const delay = base + base * 0.2 * Math.random();
        this.retryCount++;
        setTimeout(() => this._doConnect(), delay);
    },

    _setStatus(state, text) {
        const el = document.getElementById('connection-status');
        if (el) { el.className = 'status-' + state; el.textContent = text; }
    },

    send(obj) {
        if (this.ws && this.ws.readyState === WebSocket.OPEN) {
            this.ws.send(JSON.stringify(obj));
        }
    },

    disconnect() {
        this.retryCount = 999;
        if (this.ws) this.ws.close();
        document.getElementById('login-screen').style.display = 'flex';
        document.getElementById('app-screen').style.display = 'none';
    }
};

// Login
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
