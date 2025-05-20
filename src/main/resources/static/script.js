const editor = CodeMirror.fromTextArea(document.getElementById('codeEditor'), {
    lineNumbers: true,
    mode: 'text/x-c++src',
    theme: 'twilight',
    tabSize: 4,
    indentWithTabs: false
});

// Set default interactive C++ code
//editor.setValue('Start writing from here...');

const languageSelect = document.getElementById('languageSelect');
const runButton = document.getElementById('runButton');
const terminalOutput = document.getElementById('terminal-output');
const terminal = document.querySelector('.terminal');
const editorFontSizeSlider = document.getElementById('editorFontSize');
const terminalFontSizeSlider = document.getElementById('terminalFontSize');
const editorFontSizeValue = document.getElementById('editorFontSizeValue');
const terminalFontSizeValue = document.getElementById('terminalFontSizeValue');
const ws = new WebSocket('ws://' + location.host + '/api/terminal');

let currentInput = '';
let isWaitingForInput = false;

// Font size adjustment
editorFontSizeSlider.addEventListener('input', () => {
    const fontSize = editorFontSizeSlider.value + 'px';
    editorFontSizeValue.textContent = editorFontSizeSlider.value;
    editor.getWrapperElement().style.fontSize = fontSize;
    editor.refresh();
});

terminalFontSizeSlider.addEventListener('input', () => {
    const fontSize = terminalFontSizeSlider.value + 'px';
    terminalFontSizeValue.textContent = terminalFontSizeSlider.value;
    terminal.style.fontSize = fontSize;
});

ws.onopen = () => console.log('WebSocket connected');
ws.onclose = () => console.log('WebSocket disconnected');
ws.onerror = (error) => console.error('WebSocket error:', error);

ws.onmessage = (event) => {
    const data = event.data;
    const outputSpan = document.createElement('span');
    outputSpan.className = 'output';
    outputSpan.textContent = data;
    terminalOutput.appendChild(outputSpan);
    terminalOutput.scrollTop = terminalOutput.scrollHeight;

    if (data.includes('Execution finished') || data.includes('Execution terminated')) {
        runButton.disabled = false;
        isWaitingForInput = false;
    } else if (data.trim().endsWith(':')) {
        isWaitingForInput = true;
        const promptSpan = document.createElement('span');
        promptSpan.className = 'input-prompt';
        promptSpan.textContent = '> ';
        terminalOutput.appendChild(promptSpan);
        terminalOutput.scrollTop = terminalOutput.scrollHeight;
        terminal.focus();
    }
};

runButton.addEventListener('click', () => {
    if (ws.readyState === WebSocket.OPEN) {
        runButton.disabled = true;
        terminalOutput.textContent = '';
        currentInput = '';
        isWaitingForInput = false;
        const language = languageSelect.value;
        const code = editor.getValue();
        ws.send('RUN ' + language + ' ' + code);
    } else {
        const outputSpan = document.createElement('span');
        outputSpan.className = 'output';
        outputSpan.textContent = 'WebSocket not connected. Try refreshing the page.\n';
        terminalOutput.appendChild(outputSpan);
    }
});

terminal.addEventListener('keydown', (e) => {
    // Handle Ctrl+C to stop execution
    if (e.ctrlKey && e.key === 'c') {
        e.preventDefault();
        if (ws.readyState === WebSocket.OPEN) {
            ws.send('STOP');
            runButton.disabled = false;
            isWaitingForInput = false;
            currentInput = '';
            updateInputDisplay();
        }
        return;
    }

    if (!isWaitingForInput) return;

    if (e.key === 'Enter') {
        e.preventDefault();
        ws.send('INPUT ' + currentInput + '\n');
        currentInput = '';
        isWaitingForInput = false;
        terminalOutput.appendChild(document.createElement('br'));
        terminalOutput.scrollTop = terminalOutput.scrollHeight;
    } else if (e.key === 'Backspace') {
        e.preventDefault();
        currentInput = currentInput.slice(0, -1);
        updateInputDisplay();
    } else if (e.key.length === 1) {
        e.preventDefault();
        currentInput += e.key;
        updateInputDisplay();
    }
});

function updateInputDisplay() {
    const lastPrompt = terminalOutput.lastChild;
    if (lastPrompt && lastPrompt.className === 'input-prompt') {
        const inputSpan = document.createElement('span');
        inputSpan.className = 'input-text';
        inputSpan.textContent = currentInput;
        terminalOutput.appendChild(inputSpan);
    } else {
        while (terminalOutput.lastChild && terminalOutput.lastChild.className === 'input-text') {
            terminalOutput.removeChild(terminalOutput.lastChild);
        }
        const inputSpan = document.createElement('span');
        inputSpan.className = 'input-text';
        inputSpan.textContent = currentInput;
        terminalOutput.appendChild(inputSpan);
    }
    terminalOutput.scrollTop = terminalOutput.scrollHeight;
}