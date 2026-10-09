import React from "react";
import ReactDOM from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import App from "./App";
import { ColorModeProvider } from "./lib/colorMode";
import { DatePickersProvider } from "./shared/ui/components/DateInputs";
import "./index.css";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <BrowserRouter>
      <ColorModeProvider>
        <DatePickersProvider>
          <App />
        </DatePickersProvider>
      </ColorModeProvider>
    </BrowserRouter>
  </React.StrictMode>,
);
