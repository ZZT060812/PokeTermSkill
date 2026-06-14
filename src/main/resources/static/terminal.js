// xterm.js terminal manager
const Terminal = {
    term: null,
    fitAddon: null,

    init() {
        if (this.term) {
            this.term.dispose();
        }
        this.term = new Terminal({
            cursorBlink: true,
            cursorStyle: 'bar',
            fontSize: window.innerWidth <= 768 ? 12 : 14,
            fontFamily: 'Menlo, Monaco, "Courier New", monospace',
            theme: {
                background: '#1e1e2e',
                foreground: '#cdd6f4',
                cursor: '#f5e0dc',
                selectionBackground: '#585b70',
                black: '#45475a',
                red: '#f38ba8',
                green: '#a6e3a1',
                yellow: '#f9e2af',
                blue: '#89b4fa',
                magenta: '#f5c2e7',
                cyan: '#94e2d5',
                white: '#bac2de',
                brightBlack: '#585b70',
                brightRed: '#f38ba8',
                brightGreen: '#a6e3a1',
                brightYellow: '#f9e2af',
                brightBlue: '#89b4fa',
                brightMagenta: '#f5c2e7',
                brightCyan: '#94e2d5',
                brightWhite: '#a6adc8'
            }
        });

        this.fitAddon = new FitAddon.FitAddon();
        this.term.loadAddon(this.fitAddon);
        this.term.open(document.getElementById('terminal'));
        this.fitAddon.fit();

        this.term.onData((data) => {
            App.send({
                type: 'term.input',
                data: btoa(unescape(encodeURIComponent(data)))
            });
        });

        window.addEventListener('resize', this._onResize.bind(this));
    },

    sendInit(fresh) {
        const dims = this.term.cols && this.term.rows
            ? { cols: this.term.cols, rows: this.term.rows }
            : { cols: 80, rows: 24 };
        App.send({ type: 'term.init', ...dims, fresh: fresh });
    },

    write(base64) {
        if (!this.term) return;
        const data = decodeURIComponent(escape(atob(base64)));
        this.term.write(data);
    },

    replay(base64) {
        if (!this.term) return;
        this.term.reset();
        const data = decodeURIComponent(escape(atob(base64)));
        this.term.write(data);
    },

    _onResize() {
        if (this.fitAddon) {
            this.fitAddon.fit();
            App.send({
                type: 'term.resize',
                cols: this.term.cols,
                rows: this.term.rows
            });
        }
    }
};
