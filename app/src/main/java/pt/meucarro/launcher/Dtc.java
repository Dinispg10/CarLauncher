package pt.meucarro.launcher;

import java.util.HashMap;
import java.util.Map;

/** Códigos de avaria do motor (ex.: P0401): descodificar e explicar em português. */
public final class Dtc {

    /** 1 = pode andar, ver quando puder · 2 = ir à oficina em breve · 3 = parar em segurança. */
    public static final class Info {
        public final String text;
        public final int level;

        Info(String text, int level) {
            this.text = text;
            this.level = level;
        }

        public String advice() {
            switch (level) {
                case 3:
                    return "Grave: pare o carro em segurança e chame assistência.";
                case 2:
                    return "Leve o carro à oficina em breve. Evite acelerações fortes.";
                default:
                    return "Pode continuar a andar. Mostre na próxima ida à oficina.";
            }
        }
    }

    private static final Map<String, Info> KNOWN = new HashMap<>();

    private static void k(String code, String text, int level) {
        KNOWN.put(code, new Info(text, level));
    }

    static {
        k("P0016", "Sincronização entre a cambota e a árvore de cames", 2);
        k("P0045", "Turbo: circuito de comando da pressão", 2);
        k("P0087", "Pressão do gasóleo na rampa demasiado baixa (filtro de gasóleo ou bomba)", 2);
        k("P0088", "Pressão do gasóleo na rampa demasiado alta", 2);
        k("P0089", "Regulador da pressão do gasóleo: funcionamento", 2);
        k("P0093", "Fuga de gasóleo detetada", 3);
        k("P0100", "Medidor de massa de ar (debímetro): circuito", 2);
        k("P0101", "Medidor de massa de ar (debímetro): valores fora do normal", 2);
        k("P0102", "Medidor de massa de ar (debímetro): sinal baixo", 2);
        k("P0103", "Medidor de massa de ar (debímetro): sinal alto", 2);
        k("P0105", "Sensor de pressão do turbo/admissão: circuito", 2);
        k("P0106", "Sensor de pressão do turbo/admissão: valores fora do normal", 2);
        k("P0107", "Sensor de pressão do turbo/admissão: sinal baixo", 2);
        k("P0108", "Sensor de pressão do turbo/admissão: sinal alto", 2);
        k("P0110", "Sensor de temperatura do ar de admissão: circuito", 1);
        k("P0112", "Sensor de temperatura do ar de admissão: sinal baixo", 1);
        k("P0113", "Sensor de temperatura do ar de admissão: sinal alto", 1);
        k("P0115", "Sensor de temperatura do motor: circuito", 2);
        k("P0116", "Sensor de temperatura do motor: valores fora do normal", 2);
        k("P0117", "Sensor de temperatura do motor: sinal baixo", 2);
        k("P0118", "Sensor de temperatura do motor: sinal alto", 2);
        k("P0128", "Termóstato: o motor demora demasiado a aquecer", 1);
        k("P0190", "Sensor de pressão do gasóleo na rampa: circuito", 2);
        k("P0191", "Sensor de pressão do gasóleo na rampa: valores fora do normal", 2);
        k("P0192", "Sensor de pressão do gasóleo na rampa: sinal baixo", 2);
        k("P0193", "Sensor de pressão do gasóleo na rampa: sinal alto", 2);
        k("P0200", "Injetores: circuito", 2);
        k("P0201", "Injetor do cilindro 1: circuito", 2);
        k("P0202", "Injetor do cilindro 2: circuito", 2);
        k("P0203", "Injetor do cilindro 3: circuito", 2);
        k("P0204", "Injetor do cilindro 4: circuito", 2);
        k("P0234", "Pressão do turbo demasiado alta", 2);
        k("P0237", "Sensor de pressão do turbo: sinal baixo", 2);
        k("P0238", "Sensor de pressão do turbo: sinal alto", 2);
        k("P0299", "Pressão do turbo demasiado baixa (falta de força; fuga de ar ou turbo)", 2);
        k("P0300", "Falhas de combustão em vários cilindros", 2);
        k("P0301", "Falha de combustão no cilindro 1", 2);
        k("P0302", "Falha de combustão no cilindro 2", 2);
        k("P0303", "Falha de combustão no cilindro 3", 2);
        k("P0304", "Falha de combustão no cilindro 4", 2);
        k("P0335", "Sensor de rotação da cambota", 3);
        k("P0340", "Sensor da árvore de cames", 2);
        k("P0380", "Velas de incandescência: circuito (pior arranque a frio)", 1);
        k("P0381", "Luz das velas de incandescência: circuito", 1);
        k("P0400", "Válvula EGR: fluxo", 1);
        k("P0401", "Válvula EGR: fluxo insuficiente (muitas vezes sujidade)", 1);
        k("P0402", "Válvula EGR: fluxo excessivo", 2);
        k("P0403", "Válvula EGR: circuito", 1);
        k("P0404", "Válvula EGR: funcionamento", 1);
        k("P0405", "Sensor de posição da EGR: sinal baixo", 1);
        k("P0406", "Sensor de posição da EGR: sinal alto", 1);
        k("P0420", "Catalisador: eficiência abaixo do normal", 1);
        k("P0480", "Ventilador do radiador: circuito", 2);
        k("P0500", "Sensor de velocidade do carro", 1);
        k("P0501", "Sensor de velocidade do carro: valores fora do normal", 1);
        k("P0560", "Tensão da bateria fora do normal", 2);
        k("P0562", "Tensão da bateria baixa (bateria ou alternador)", 2);
        k("P0563", "Tensão da bateria alta", 2);
        k("P0571", "Interruptor do pedal do travão", 1);
        k("P0606", "Centralina do motor: avaria interna", 2);
        k("P0627", "Bomba de gasóleo: circuito", 2);
        k("P0641", "Tensão de referência dos sensores (A)", 2);
        k("P0651", "Tensão de referência dos sensores (B)", 2);
        k("P0670", "Módulo das velas de incandescência", 1);
        k("P0671", "Vela de incandescência do cilindro 1", 1);
        k("P0672", "Vela de incandescência do cilindro 2", 1);
        k("P0673", "Vela de incandescência do cilindro 3", 1);
        k("P0674", "Vela de incandescência do cilindro 4", 1);
        k("P2002", "Filtro de partículas: eficiência abaixo do normal", 2);
        k("P2015", "Borboletas do coletor de admissão: sensor de posição", 1);
        k("P2100", "Motor da borboleta de admissão: circuito", 2);
        k("P2263", "Turbo: pressão fora do esperado", 2);
        k("P2279", "Fuga de ar na admissão", 2);
        k("P242F", "Filtro de partículas: restrição por cinzas (filtro no fim de vida)", 2);
        k("P2452", "Sensor de pressão do filtro de partículas: circuito", 2);
        k("P2453", "Sensor de pressão do filtro de partículas: valores fora do normal", 2);
        k("P2454", "Sensor de pressão do filtro de partículas: sinal baixo", 2);
        k("P2455", "Sensor de pressão do filtro de partículas: sinal alto", 2);
        k("P2458", "Filtro de partículas: regeneração demorou demasiado", 2);
        k("P2463", "Filtro de partículas cheio de fuligem. Ajuda: 20 min em autoestrada acima das 2500 rpm; "
                + "se não apagar, oficina", 2);
        k("P2563", "Turbo de geometria variável: sensor de posição", 2);
    }

    private Dtc() {
    }

    /** Dois bytes do carro → "P0401". */
    public static String decode(int a, int b) {
        char[] first = {'P', 'C', 'B', 'U'};
        return "" + first[(a >> 6) & 3] + ((a >> 4) & 3)
                + Integer.toHexString(a & 0xF).toUpperCase()
                + Integer.toHexString((b >> 4) & 0xF).toUpperCase()
                + Integer.toHexString(b & 0xF).toUpperCase();
    }

    public static Info info(String code) {
        Info known = KNOWN.get(code);
        if (known != null) return known;
        if (code.length() != 5) return new Info("Código desconhecido", 1);
        char sys = code.charAt(0);
        if (sys == 'U') return new Info("Falha de comunicação entre módulos do carro", 1);
        if (sys == 'C') return new Info("Chassis (travões/ABS ou direção)", 2);
        if (sys == 'B') return new Info("Carroçaria (conforto, airbag…)", 1);
        char kind = code.charAt(1);
        char group = code.charAt(2);
        String area;
        if (kind == '1' || kind == '3') {
            area = "Código próprio da VW";
        } else if (kind == '2') {
            area = "Admissão, turbo ou emissões";
        } else {
            switch (group) {
                case '0':
                case '1':
                    area = "Mistura ar/gasóleo e medição de ar";
                    break;
                case '2':
                    area = "Injetores ou circuito de gasóleo";
                    break;
                case '3':
                    area = "Arranque, rotação ou combustão";
                    break;
                case '4':
                    area = "Emissões (EGR, filtro de partículas, catalisador)";
                    break;
                case '5':
                    area = "Velocidade, ralenti ou tensão da bateria";
                    break;
                case '6':
                    area = "Centralina ou circuitos de comando";
                    break;
                case '7':
                case '8':
                case '9':
                    area = "Caixa de velocidades";
                    break;
                default:
                    area = "Motor";
            }
        }
        return new Info(area + " (sem descrição exata)", 2);
    }
}
