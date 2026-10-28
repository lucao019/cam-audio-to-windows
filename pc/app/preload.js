const { contextBridge, ipcRenderer } = require("electron");

// Ponte minima entre a UI (HTML) e o processo principal.
contextBridge.exposeInMainWorld("lucao", {
    minimizar: () => ipcRenderer.send("janela:minimizar"),
    maximizar: () => ipcRenderer.send("janela:maximizar"),
    fechar: () => ipcRenderer.send("janela:fechar"),
    info: () => ipcRenderer.invoke("app:info"),
    aoMaximizar: (callback) => {
        const ouvinte = (_evento, maximizada) => callback(maximizada);

        ipcRenderer.on("janela:maximizada", ouvinte);

        return () => ipcRenderer.removeListener("janela:maximizada", ouvinte);
    }
});
