library IEEE;
use IEEE.STD_LOGIC_1164.ALL;
use IEEE.NUMERIC_STD.ALL;

-- ============================================================================
-- Module Name: pulser_rtz (Top-Level Structural Entity)
-- Description: Modular top-level integration for the Ultrasound Imaging Device.
--              Coordinates the Pulser State Machine, Time-Gain Compensation (TGC),
--              Demodulation & Envelope DSP Pipeline, Acquisition Line BRAM & UART TX,
--              UART RX Command Decoder, and Position-Triggered Stepper Gantry Engine.
--
--              Position-Triggered Pulser & Hardware Gated Scanning Engine:
--              - pulser_fsm transitions to STRIKE strictly upon pos_pulse_trigger from
--                stepper_controller during autonomous chamber scan sweeps.
--              - Hardware Gating suppresses all pulse emissions during Y-axis line indexing.
--              - Full backward-compatible free-running 100 Hz PRF preserved during idle.
--              - Flow control handshake via acq_busy eliminates frame drops.
-- ============================================================================
entity pulser_rtz is
    Port (
        CLK100MHZ : in  STD_LOGIC;

        -- High Voltage Pulser & Protection Hardware
        relay     : out STD_LOGIC;
        oe        : out STD_LOGIC;
        ina       : out STD_LOGIC;
        inb       : out STD_LOGIC;

        -- Onboard Status Indicators
        rgb_led   : out STD_LOGIC_VECTOR(5 downto 0);

        -- Analog Time-Gain Compensation SPI DAC
        cs        : out STD_LOGIC;
        sck       : out STD_LOGIC;
        sci       : out STD_LOGIC;

        -- High-Speed Parallel Ultrasound ADC Interface
        clk       : out STD_LOGIC; -- 20 MHz ADC Clock Out
        adc_data  : in  STD_LOGIC_VECTOR(12 downto 1);
        otr       : in  STD_LOGIC;

        -- 2,000,000 Baud Host Communication
        uart_tx   : out STD_LOGIC;
        uart_rx   : in  STD_LOGIC;

        -- Stepper Motor Interface (Dual Y-Axis Lead Screws & X-Axis)
        -- Motor 1 (Y1 Lead Screw)
        io0       : out STD_LOGIC; -- Y1 DIR
        io1       : out STD_LOGIC; -- Y1 STEP
        io2       : out STD_LOGIC; -- Y1 MS1 (HIGH = 1/16 microstep)
        io3       : out STD_LOGIC; -- Y1 MS2 (HIGH = 1/16 microstep)
        io4       : out STD_LOGIC; -- Y1 MS3 (HIGH = 1/16 microstep)
        io5       : out STD_LOGIC; -- Y1 SLEEP (HIGH = Awake)
        io6       : out STD_LOGIC; -- Y1 RESET (HIGH = Run)

        -- Motor 2 (Y2 Lead Screw: Hardware Locked in Lockstep with Y1)
        io7       : out STD_LOGIC; -- Y2 DIR
        io8       : out STD_LOGIC; -- Y2 STEP
        io9       : out STD_LOGIC; -- Y2 MS1 (HIGH = 1/16 microstep)
        io10      : out STD_LOGIC; -- Y2 MS2 (HIGH = 1/16 microstep)
        io11      : out STD_LOGIC; -- Y2 MS3 (HIGH = 1/16 microstep)
        io12      : out STD_LOGIC; -- Y2 SLEEP (HIGH = Awake)
        io13      : out STD_LOGIC; -- Y2 RESET (HIGH = Run)

        -- Motor 3 (Top X-Axis Carriage)
        io26      : out STD_LOGIC; -- X DIR
        io27      : out STD_LOGIC; -- X STEP
        io28      : out STD_LOGIC; -- X MS1 (HIGH = 1/16 microstep)
        io29      : out STD_LOGIC; -- X MS2 (HIGH = 1/16 microstep)
        io30      : out STD_LOGIC; -- X MS3 (HIGH = 1/16 microstep)
        io31      : out STD_LOGIC; -- X SLEEP (HIGH = Awake)
        io32      : out STD_LOGIC; -- X RESET (HIGH = Run)
        io33      : out STD_LOGIC  -- X PULLUP / AUX
    );
end pulser_rtz;

architecture Structural of pulser_rtz is

    -- 1. High Voltage Pulser & Sequence Controller
    component pulser_fsm is
        Port (
            clk100mhz         : in  STD_LOGIC;
            spi_init_done     : in  STD_LOGIC;
            scan_mode_active  : in  STD_LOGIC;
            pos_pulse_trigger : in  STD_LOGIC;
            relay             : out STD_LOGIC;
            oe                : out STD_LOGIC;
            ina               : out STD_LOGIC;
            inb               : out STD_LOGIC;
            heartbeat         : out STD_LOGIC;
            pulse_trigger     : out STD_LOGIC
        );
    end component;

    -- 2. Time-Gain Compensation SPI DAC Controller
    component tgc_controller is
        Port (
            clk100mhz     : in  STD_LOGIC;
            pulse_trigger : in  STD_LOGIC;
            cs            : out STD_LOGIC;
            sck           : out STD_LOGIC;
            sci           : out STD_LOGIC;
            spi_init_done : out STD_LOGIC
        );
    end component;

    -- 3. Demodulation, Filtering & Envelope DSP Pipeline
    component dsp_pipeline is
        Port (
            clk100mhz    : in  STD_LOGIC;
            adc_data     : in  STD_LOGIC_VECTOR(12 downto 1);
            otr          : in  STD_LOGIC;
            adc_clk      : out STD_LOGIC;
            sample_out   : out STD_LOGIC_VECTOR(7 downto 0);
            sample_valid : out STD_LOGIC
        );
    end component;

    -- 4. A-Scan Line BRAM Buffer & High-Speed UART TX
    component acquisition_ram is
        Port (
            clk100mhz     : in  STD_LOGIC;
            pulse_trigger : in  STD_LOGIC;
            sample_data   : in  STD_LOGIC_VECTOR(7 downto 0);
            sample_valid  : in  STD_LOGIC;
            uart_tx       : out STD_LOGIC;
            busy          : out STD_LOGIC
        );
    end component;

    -- 5. Command Receiver & Packet Protocol Decoder
    component uart_rx_protocol is
        Port (
            clk100mhz    : in  STD_LOGIC;
            uart_rx      : in  STD_LOGIC;
            cmd_strobe   : out STD_LOGIC;
            cmd_id       : out STD_LOGIC_VECTOR(7 downto 0);
            cmd_axis     : out STD_LOGIC_VECTOR(7 downto 0);
            cmd_dir      : out STD_LOGIC;
            param1       : out unsigned(31 downto 0);
            param2       : out unsigned(31 downto 0);
            param3       : out unsigned(15 downto 0)
        );
    end component;

    -- 6. Dual-Y and X Stepper Motion & Position-Triggered Chamber Scanner
    component stepper_controller is
        Port (
            clk100mhz          : in  STD_LOGIC;
            cmd_strobe         : in  STD_LOGIC;
            cmd_id             : in  STD_LOGIC_VECTOR(7 downto 0);
            cmd_axis           : in  STD_LOGIC_VECTOR(7 downto 0);
            cmd_dir            : in  STD_LOGIC;
            param1             : in  unsigned(31 downto 0);
            param2             : in  unsigned(31 downto 0);
            param3             : in  unsigned(15 downto 0);

            acq_busy           : in  STD_LOGIC;
            pos_pulse_trigger  : out STD_LOGIC;
            scan_mode_active   : out STD_LOGIC;

            io0                : out STD_LOGIC;
            io1                : out STD_LOGIC;
            io2                : out STD_LOGIC;
            io3                : out STD_LOGIC;
            io4                : out STD_LOGIC;
            io5                : out STD_LOGIC;
            io6                : out STD_LOGIC;

            io7                : out STD_LOGIC;
            io8                : out STD_LOGIC;
            io9                : out STD_LOGIC;
            io10               : out STD_LOGIC;
            io11               : out STD_LOGIC;
            io12               : out STD_LOGIC;
            io13               : out STD_LOGIC;

            io26               : out STD_LOGIC;
            io27               : out STD_LOGIC;
            io28               : out STD_LOGIC;
            io29               : out STD_LOGIC;
            io30               : out STD_LOGIC;
            io31               : out STD_LOGIC;
            io32               : out STD_LOGIC;
            io33               : out STD_LOGIC;

            is_scanning        : out STD_LOGIC
        );
    end component;

    -- Internal Interconnect Signals
    signal spi_init_done_sig     : STD_LOGIC;
    signal pulse_trigger_sig     : STD_LOGIC;
    signal heartbeat_sig         : STD_LOGIC;
    signal relay_sig             : STD_LOGIC;

    signal sample_data_sig       : STD_LOGIC_VECTOR(7 downto 0);
    signal sample_valid_sig      : STD_LOGIC;

    signal cmd_strobe_sig        : STD_LOGIC;
    signal cmd_id_sig            : STD_LOGIC_VECTOR(7 downto 0);
    signal cmd_axis_sig          : STD_LOGIC_VECTOR(7 downto 0);
    signal cmd_dir_sig           : STD_LOGIC;
    signal param1_sig            : unsigned(31 downto 0);
    signal param2_sig            : unsigned(31 downto 0);
    signal param3_sig            : unsigned(15 downto 0);

    signal is_scanning_sig       : STD_LOGIC;
    signal pos_pulse_trigger_sig : STD_LOGIC;
    signal scan_mode_active_sig  : STD_LOGIC;
    signal acq_busy_sig          : STD_LOGIC;

begin

    -- Status LED assignments
    relay      <= relay_sig;
    rgb_led(0) <= is_scanning_sig; -- Active blue/green during autonomous chamber scan
    rgb_led(1) <= heartbeat_sig;   -- 1 Hz PRF heartbeat
    rgb_led(2) <= '0';
    rgb_led(3) <= '0';
    rgb_led(4) <= relay_sig;       -- High-Voltage Relay engaged indicator
    rgb_led(5) <= '0';

    -- 1. High Voltage Pulser State Machine
    u_pulser_fsm: pulser_fsm
        port map (
            clk100mhz         => CLK100MHZ,
            spi_init_done     => spi_init_done_sig,
            scan_mode_active  => scan_mode_active_sig,
            pos_pulse_trigger => pos_pulse_trigger_sig,
            relay             => relay_sig,
            oe                => oe,
            ina               => ina,
            inb               => inb,
            heartbeat         => heartbeat_sig,
            pulse_trigger     => pulse_trigger_sig
        );

    -- 2. Time-Gain Compensation SPI DAC
    u_tgc_controller: tgc_controller
        port map (
            clk100mhz     => CLK100MHZ,
            pulse_trigger => pulse_trigger_sig,
            cs            => cs,
            sck           => sck,
            sci           => sci,
            spi_init_done => spi_init_done_sig
        );

    -- 3. Baseband Demodulation, CORDIC & Envelope DSP Pipeline
    u_dsp_pipeline: dsp_pipeline
        port map (
            clk100mhz    => CLK100MHZ,
            adc_data     => adc_data,
            otr          => otr,
            adc_clk      => clk,
            sample_out   => sample_data_sig,
            sample_valid => sample_valid_sig
        );

    -- 4. Acquisition Line Buffer & 2 Mbaud UART Transmitter
    u_acquisition_ram: acquisition_ram
        port map (
            clk100mhz     => CLK100MHZ,
            pulse_trigger => pulse_trigger_sig,
            sample_data   => sample_data_sig,
            sample_valid  => sample_valid_sig,
            uart_tx       => uart_tx,
            busy          => acq_busy_sig
        );

    -- 5. Command Receiver & Packet Protocol Decoder
    u_uart_rx: uart_rx_protocol
        port map (
            clk100mhz    => CLK100MHZ,
            uart_rx      => uart_rx,
            cmd_strobe   => cmd_strobe_sig,
            cmd_id       => cmd_id_sig,
            cmd_axis     => cmd_axis_sig,
            cmd_dir      => cmd_dir_sig,
            param1       => param1_sig,
            param2       => param2_sig,
            param3       => param3_sig
        );

    -- 6. Dual-Y & X Stepper Motion & Position-Triggered Chamber Scanner
    u_stepper_controller: stepper_controller
        port map (
            clk100mhz          => CLK100MHZ,
            cmd_strobe         => cmd_strobe_sig,
            cmd_id             => cmd_id_sig,
            cmd_axis           => cmd_axis_sig,
            cmd_dir            => cmd_dir_sig,
            param1             => param1_sig,
            param2             => param2_sig,
            param3             => param3_sig,

            acq_busy           => acq_busy_sig,
            pos_pulse_trigger  => pos_pulse_trigger_sig,
            scan_mode_active   => scan_mode_active_sig,

            io0                => io0,
            io1                => io1,
            io2                => io2,
            io3                => io3,
            io4                => io4,
            io5                => io5,
            io6                => io6,

            io7                => io7,
            io8                => io8,
            io9                => io9,
            io10               => io10,
            io11               => io11,
            io12               => io12,
            io13               => io13,

            io26               => io26,
            io27               => io27,
            io28               => io28,
            io29               => io29,
            io30               => io30,
            io31               => io31,
            io32               => io32,
            io33               => io33,

            is_scanning        => is_scanning_sig
        );

end Structural;
