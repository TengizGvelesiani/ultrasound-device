library IEEE;
use IEEE.STD_LOGIC_1164.ALL;
use IEEE.NUMERIC_STD.ALL;

-- ============================================================================
-- Module Name: stepper_controller
-- Description: Stepper Motion Controller & Position-Triggered Chamber Scanner.
--              Generates step pulses for X-axis and dual locked Y-axis lead screws (Y1+Y2),
--              maintains 1/16 microstepping drivers, executes manual jog commands,
--              and coordinates the autonomous zigzag raster trajectory across the chamber.
--
--              Hardware-Synchronized Frame Metadata & Power Management:
--              - Provides exact frame status flags (Bit 7: scanning, Bit 6: complete,
--                Bit 5: line_start, Bit 4: line_end, Bit 3: dir_x, Bits 2..0: line_idx)
--                to acquisition_ram so every UART packet is tagged in hardware.
--              - On chamber scan completion, asserts scan_done_hold to keep pulser
--                completely silent, eliminates startup motor jolt, and automatically
--                powers down driver coils after settling in M_HOLD.
--              - Supports runtime initial start direction selection (+X/-X, +Y/-Y).
-- ============================================================================
entity stepper_controller is
    Port (
        clk100mhz          : in  STD_LOGIC;

        -- Decoded Commands from uart_rx_protocol
        cmd_strobe         : in  STD_LOGIC;
        cmd_id             : in  STD_LOGIC_VECTOR(7 downto 0);
        cmd_axis           : in  STD_LOGIC_VECTOR(7 downto 0);
        cmd_dir            : in  STD_LOGIC_VECTOR(7 downto 0);
        param1             : in  unsigned(31 downto 0);
        param2             : in  unsigned(31 downto 0);
        param3             : in  unsigned(15 downto 0);

        -- Hardware Handshake with Acquisition & Pulser
        acq_busy           : in  STD_LOGIC;
        pos_pulse_trigger  : out STD_LOGIC;
        scan_mode_active   : out STD_LOGIC;
        scan_status        : out STD_LOGIC_VECTOR(7 downto 0);
        scan_done_hold     : out STD_LOGIC;

        -- Motor 1 (Bottom Y1 Lead Screw) Hardware Interface
        io0                : out STD_LOGIC; -- Y1 DIR
        io1                : out STD_LOGIC; -- Y1 STEP
        io2                : out STD_LOGIC; -- Y1 MS1 (HIGH = 1/16 microstep)
        io3                : out STD_LOGIC; -- Y1 MS2 (HIGH = 1/16 microstep)
        io4                : out STD_LOGIC; -- Y1 MS3 (HIGH = 1/16 microstep)
        io5                : out STD_LOGIC; -- Y1 RESET ('1' = Run, '0' = Reset/Disabled)
        io6                : out STD_LOGIC; -- Y1 SLEEP ('1' = Awake, '0' = Sleep/Idle)

        -- Motor 2 (Bottom Y2 Lead Screw) Hardware Interface (Locked in Unison)
        io7                : out STD_LOGIC; -- Y2 DIR
        io8                : out STD_LOGIC; -- Y2 STEP
        io9                : out STD_LOGIC; -- Y2 SLEEP ('1' = Awake, '0' = Sleep/Idle)
        io10               : out STD_LOGIC; -- Y2 RESET ('1' = Run, '0' = Reset/Disabled)
        io11               : out STD_LOGIC; -- Y2 MS1 (HIGH = 1/16 microstep)
        io12               : out STD_LOGIC; -- Y2 MS2 (HIGH = 1/16 microstep)
        io13               : out STD_LOGIC; -- Y2 MS3 (HIGH = 1/16 microstep)

        -- Motor 3 (Top X-Axis Carriage) Hardware Interface
        io26               : out STD_LOGIC; -- X DIR
        io27               : out STD_LOGIC; -- X STEP
        io28               : out STD_LOGIC; -- X MS1 (HIGH = 1/16 microstep)
        io29               : out STD_LOGIC; -- X MS2 (HIGH = 1/16 microstep)
        io30               : out STD_LOGIC; -- X MS3 (HIGH = 1/16 microstep)
        io31               : out STD_LOGIC; -- X SLEEP ('1' = Awake, '0' = Sleep/Idle)
        io32               : out STD_LOGIC; -- X RESET ('1' = Run, '0' = Reset/Disabled)
        io33               : out STD_LOGIC; -- X PULLUP / AUX

        -- Status Indicator
        is_scanning        : out STD_LOGIC
    );
end stepper_controller;

architecture Behavioral of stepper_controller is

    -- Step Timer & Frequency Control (Default: 31,250 cycles = 1,600 Hz step frequency)
    signal cfg_half_period     : integer := 31_250;
    signal step_timer_x        : integer := 0;
    signal step_timer_y        : integer := 0;
    signal step_phase_x        : STD_LOGIC := '0';
    signal step_phase_y        : STD_LOGIC := '0';

    -- Physical Driver Output Registers
    signal x_step_r            : STD_LOGIC := '0';
    signal x_dir_r             : STD_LOGIC := '0';
    signal y_step_r            : STD_LOGIC := '0';
    signal y_dir_r             : STD_LOGIC := '0';

    -- Driver Power Management & Stabilization Timers @ 100 MHz
    constant WAKEUP_DELAY_CYCLES : integer := 200_000;    -- 2.0 ms charge pump stabilization
    constant IDLE_HOLD_CYCLES    : integer := 50_000_000;  -- 0.5 s holding torque settling window
    signal wake_timer            : integer range 0 to WAKEUP_DELAY_CYCLES := 0;
    signal hold_timer            : integer range 0 to IDLE_HOLD_CYCLES := 0;
    signal motor_enable_r        : STD_LOGIC := '0';       -- '0' = Low-power sleep, '1' = Awake/Energized

    -- Motion State Machine
    type motor_mode_type is (
        M_IDLE,
        M_WAKEUP,
        M_JOG_X,
        M_JOG_Y,
        M_SCAN_SWEEP_X,
        M_SCAN_STEP_Y,
        M_SCAN_COMPLETE,
        M_HOLD
    );
    signal motor_mode          : motor_mode_type := M_IDLE;
    signal next_motion_mode    : motor_mode_type := M_IDLE;
    signal jog_steps_rem_x     : unsigned(31 downto 0) := (others => '0');
    signal jog_steps_rem_y     : unsigned(31 downto 0) := (others => '0');

    -- Position Trigger & Hardware Gating Registers
    signal pos_pulse_trigger_r : STD_LOGIC := '0';
    signal scan_delta_x_steps  : unsigned(15 downto 0) := to_unsigned(40, 16); -- 40 microsteps = 0.1 mm @ 400 stp/mm
    signal pulse_step_cnt      : unsigned(15 downto 0) := (others => '0');

    -- Autonomous Chamber Scan Trajectory Registers
    signal scan_total_x_steps  : unsigned(31 downto 0) := to_unsigned(40_000, 32); -- 100mm @ 400 stp/mm
    signal scan_total_y_steps  : unsigned(31 downto 0) := to_unsigned(40_000, 32);
    signal scan_delta_y_steps  : unsigned(15 downto 0) := to_unsigned(400, 16);    -- 1mm
    signal scan_curr_x_steps   : unsigned(31 downto 0) := (others => '0');
    signal scan_curr_y_steps   : unsigned(31 downto 0) := (others => '0');
    signal scan_x_dir          : STD_LOGIC := '1';
    signal scan_y_dir          : STD_LOGIC := '1';

    -- Hardware Synchronization & Frame Metadata Registers
    signal scan_status_r       : STD_LOGIC_VECTOR(7 downto 0) := x"00";
    signal scan_done_hold_r    : STD_LOGIC := '0';
    signal line_counter        : unsigned(15 downto 0) := (others => '0');
    signal line_has_pulsed     : STD_LOGIC := '0';

begin

    -- Motor 1 Outputs (Y1 Lead Screw)
    io0  <= y_dir_r;
    io1  <= y_step_r;
    io2  <= '1';            -- MS1: 1/16 microstepping
    io3  <= '1';            -- MS2
    io4  <= '1';            -- MS3
    io5  <= motor_enable_r; -- Y1 RESET: '1' = Run, '0' = Outputs Disabled
    io6  <= motor_enable_r; -- Y1 SLEEP: '1' = Awake, '0' = Sleep/Idle (Silent)

    -- Motor 2 Outputs (Y2 Lead Screw: Strictly locked with Y1 in hardware)
    io7  <= y_dir_r;
    io8  <= y_step_r;
    io9  <= motor_enable_r; -- SLEEP: '1' = Awake, '0' = Sleep/Idle (Silent)
    io10 <= motor_enable_r; -- RESET: '1' = Run, '0' = Outputs Disabled
    io11 <= '1';            -- MS1: 1/16 microstepping
    io12 <= '1';            -- MS2
    io13 <= '1';            -- MS3

    -- Motor 3 Outputs (X-Axis Carriage)
    io26 <= x_dir_r;
    io27 <= x_step_r;
    io28 <= '1';            -- MS1: 1/16 microstepping
    io29 <= '1';            -- MS2
    io30 <= '1';            -- MS3
    io31 <= motor_enable_r; -- SLEEP: '1' = Awake, '0' = Sleep/Idle (Silent)
    io32 <= motor_enable_r; -- RESET: '1' = Run, '0' = Outputs Disabled
    io33 <= '1';            -- PULLUP / AUX

    -- Subsystem Synchronization
    pos_pulse_trigger <= pos_pulse_trigger_r;
    scan_mode_active  <= '1' when (motor_mode = M_SCAN_SWEEP_X or motor_mode = M_SCAN_STEP_Y or (motor_mode = M_WAKEUP and next_motion_mode = M_SCAN_SWEEP_X)) else '0';
    is_scanning       <= '1' when (motor_mode = M_SCAN_SWEEP_X or motor_mode = M_SCAN_STEP_Y or (motor_mode = M_WAKEUP and next_motion_mode = M_SCAN_SWEEP_X)) else '0';
    scan_status       <= scan_status_r;
    scan_done_hold    <= scan_done_hold_r;

    -- Synchronous Stepper Motor Execution Process
    process(clk100mhz)
    begin
        if rising_edge(clk100mhz) then
            -- Default 1-cycle strobe
            pos_pulse_trigger_r <= '0';

            -- Motion Generator State Machine
            case motor_mode is
                when M_IDLE =>
                    motor_enable_r <= '0';
                    x_step_r       <= '0';
                    y_step_r       <= '0';
                    step_phase_x   <= '0';
                    step_phase_y   <= '0';
                    pulse_step_cnt <= (others => '0');
                    if scan_done_hold_r = '0' then
                        scan_status_r <= x"00";
                    end if;

                when M_WAKEUP =>
                    -- Charge pump stabilization window before stepping starts
                    motor_enable_r <= '1';
                    x_step_r       <= '0';
                    y_step_r       <= '0';
                    if wake_timer = 0 then
                        motor_mode <= next_motion_mode;
                    else
                        wake_timer <= wake_timer - 1;
                    end if;

                when M_JOG_X =>
                    motor_enable_r <= '1';
                    scan_status_r  <= x"00";
                    if step_timer_x >= cfg_half_period - 1 then
                        step_timer_x <= 0;
                        if step_phase_x = '0' then
                            step_phase_x <= '1';
                            x_step_r     <= '1';
                            if jog_steps_rem_x <= 1 then
                                jog_steps_rem_x <= (others => '0');
                            else
                                jog_steps_rem_x <= jog_steps_rem_x - 1;
                            end if;
                        else
                            step_phase_x <= '0';
                            x_step_r     <= '0';
                            if jog_steps_rem_x = 0 then
                                hold_timer <= IDLE_HOLD_CYCLES;
                                motor_mode <= M_HOLD;
                            end if;
                        end if;
                    else
                        step_timer_x <= step_timer_x + 1;
                    end if;

                when M_JOG_Y =>
                    motor_enable_r <= '1';
                    scan_status_r  <= x"00";
                    if step_timer_y >= cfg_half_period - 1 then
                        step_timer_y <= 0;
                        if step_phase_y = '0' then
                            step_phase_y <= '1';
                            y_step_r     <= '1';
                            if jog_steps_rem_y <= 1 then
                                jog_steps_rem_y <= (others => '0');
                            else
                                jog_steps_rem_y <= jog_steps_rem_y - 1;
                            end if;
                        else
                            step_phase_y <= '0';
                            y_step_r     <= '0';
                            if jog_steps_rem_y = 0 then
                                hold_timer <= IDLE_HOLD_CYCLES;
                                motor_mode <= M_HOLD;
                            end if;
                        end if;
                    else
                        step_timer_y <= step_timer_y + 1;
                    end if;

                when M_SCAN_SWEEP_X =>
                    motor_enable_r <= '1';
                    x_dir_r <= scan_x_dir;
                    -- Flow control: hold if acquisition buffer is busy transmitting and a step is due
                    if (step_timer_x < cfg_half_period - 1) or (acq_busy = '0') then
                        if step_timer_x >= cfg_half_period - 1 then
                            step_timer_x <= 0;
                            if step_phase_x = '0' then
                                step_phase_x <= '1';
                                x_step_r     <= '1'; -- Rising edge: microstep begins

                                -- Position-Triggered Pulse: fire every N microsteps
                                if pulse_step_cnt >= scan_delta_x_steps - 1 then
                                    pulse_step_cnt      <= (others => '0');
                                    pos_pulse_trigger_r <= '1'; -- 1-cycle pulse strobe to pulser_fsm

                                    -- Update hardware frame metadata status flags for this pulse:
                                    -- Bit 7: IS_SCANNING ('1')
                                    -- Bit 6: SCAN_COMPLETE ('1' if final pulse of entire scan)
                                    -- Bit 5: LINE_START ('1' if first pulse of line)
                                    -- Bit 4: LINE_END ('1' if last pulse of line)
                                    -- Bit 3: DIR_X ('1' = +X, '0' = -X)
                                    -- Bits 2..0: line_counter(2 downto 0)
                                    scan_status_r(7) <= '1';
                                    scan_status_r(3) <= scan_x_dir;
                                    scan_status_r(2 downto 0) <= std_logic_vector(line_counter(2 downto 0));

                                    if line_has_pulsed = '0' then
                                        scan_status_r(5) <= '1'; -- LINE_START
                                        line_has_pulsed  <= '1';
                                    else
                                        scan_status_r(5) <= '0';
                                    end if;

                                    -- Check if this is the final pulse of the current line
                                    if (scan_curr_x_steps + resize(scan_delta_x_steps, 32)) >= scan_total_x_steps then
                                        scan_status_r(4) <= '1'; -- LINE_END
                                        if scan_curr_y_steps >= scan_total_y_steps then
                                            scan_status_r(6) <= '1'; -- SCAN_COMPLETE
                                        else
                                            scan_status_r(6) <= '0';
                                        end if;
                                    else
                                        scan_status_r(4) <= '0';
                                        scan_status_r(6) <= '0';
                                    end if;
                                else
                                    pulse_step_cnt <= pulse_step_cnt + 1;
                                end if;

                                -- Track coordinate displacement across chamber width
                                if scan_curr_x_steps >= scan_total_x_steps - 1 then
                                    scan_curr_x_steps <= (others => '0');
                                    pulse_step_cnt    <= (others => '0');
                                    scan_x_dir        <= not scan_x_dir; -- Invert direction for serpentine return pass
                                    line_has_pulsed   <= '0';

                                    if scan_curr_y_steps >= scan_total_y_steps then
                                        motor_mode <= M_SCAN_COMPLETE;
                                    else
                                        line_counter    <= line_counter + 1;
                                        jog_steps_rem_y <= resize(scan_delta_y_steps, 32);
                                        y_dir_r         <= scan_y_dir;
                                        step_timer_y    <= 0;
                                        step_phase_y    <= '0';
                                        motor_mode      <= M_SCAN_STEP_Y;
                                    end if;
                                else
                                    scan_curr_x_steps <= scan_curr_x_steps + 1;
                                end if;
                            else
                                step_phase_x <= '0';
                                x_step_r     <= '0'; -- Falling edge: step pin returns to 0
                            end if;
                        else
                            step_timer_x <= step_timer_x + 1;
                        end if;
                    end if;

                when M_SCAN_STEP_Y =>
                    motor_enable_r <= '1';
                    y_dir_r        <= scan_y_dir;
                    -- Hardware Gating: pos_pulse_trigger_r is strictly held at '0'.
                    pulse_step_cnt <= (others => '0');
                    if step_timer_y >= cfg_half_period - 1 then
                        step_timer_y <= 0;
                        if step_phase_y = '0' then
                            step_phase_y <= '1';
                            y_step_r     <= '1';
                            if jog_steps_rem_y <= 1 then
                                jog_steps_rem_y <= (others => '0');
                            else
                                jog_steps_rem_y <= jog_steps_rem_y - 1;
                            end if;
                        else
                            step_phase_y <= '0';
                            y_step_r     <= '0';
                            if jog_steps_rem_y = 0 then
                                scan_curr_y_steps <= scan_curr_y_steps + resize(scan_delta_y_steps, 32);
                                step_timer_x      <= 0;
                                step_phase_x      <= '0';
                                pulse_step_cnt    <= (others => '0');
                                line_has_pulsed   <= '0';
                                motor_mode        <= M_SCAN_SWEEP_X;
                            end if;
                        end if;
                    else
                        step_timer_y <= step_timer_y + 1;
                    end if;

                when M_SCAN_COMPLETE =>
                    x_step_r         <= '0';
                    y_step_r         <= '0';
                    step_phase_x     <= '0';
                    step_phase_y     <= '0';
                    pulse_step_cnt   <= (others => '0');
                    scan_done_hold_r <= '1'; -- Inhibit free-running pulser! Device enters silent hold!
                    hold_timer       <= IDLE_HOLD_CYCLES;
                    motor_mode       <= M_HOLD;

                when M_HOLD =>
                    -- Settle kinetic momentum with holding torque before going to sleep
                    x_step_r     <= '0';
                    y_step_r     <= '0';
                    step_phase_x <= '0';
                    step_phase_y <= '0';
                    if hold_timer = 0 then
                        motor_enable_r <= '0';
                        motor_mode     <= M_IDLE;
                    else
                        hold_timer <= hold_timer - 1;
                    end if;
            end case;

            -- Decode host commands (Overriding priority over automatic transitions)
            if cmd_strobe = '1' then
                case cmd_id is
                    when x"00" => -- CMD_HALT: Immediate emergency halt & disable coils
                        motor_mode       <= M_IDLE;
                        motor_enable_r   <= '0';
                        x_step_r         <= '0';
                        y_step_r         <= '0';
                        step_phase_x     <= '0';
                        step_phase_y     <= '0';
                        pulse_step_cnt   <= (others => '0');
                        wake_timer       <= 0;
                        hold_timer       <= 0;
                        scan_done_hold_r <= '0'; -- Clear hold on explicit user halt
                        scan_status_r    <= x"00";

                    when x"01" => -- CMD_JOG: Single-axis increment
                        step_phase_x     <= '0';
                        step_phase_y     <= '0';
                        scan_done_hold_r <= '0';
                        scan_status_r    <= x"00";
                        if param2 > 500 then
                            cfg_half_period <= to_integer(param2);
                        end if;

                        if cmd_axis = x"01" then -- AXIS_X
                            x_dir_r         <= cmd_dir(0);
                            jog_steps_rem_x <= param1;
                            step_timer_x    <= 0;
                            if motor_enable_r = '1' and motor_mode /= M_IDLE then
                                motor_mode <= M_JOG_X;
                            else
                                motor_enable_r   <= '1';
                                wake_timer       <= WAKEUP_DELAY_CYCLES;
                                next_motion_mode <= M_JOG_X;
                                motor_mode       <= M_WAKEUP;
                            end if;
                        elsif cmd_axis = x"02" then -- AXIS_Y (Dual bottom motors locked)
                            y_dir_r         <= cmd_dir(0);
                            jog_steps_rem_y <= param1;
                            step_timer_y    <= 0;
                            if motor_enable_r = '1' and motor_mode /= M_IDLE then
                                motor_mode <= M_JOG_Y;
                            else
                                motor_enable_r   <= '1';
                                wake_timer       <= WAKEUP_DELAY_CYCLES;
                                next_motion_mode <= M_JOG_Y;
                                motor_mode       <= M_WAKEUP;
                            end if;
                        end if;

                    when x"02" => -- CMD_CHAMBER_SCAN: Autonomous full XY serpentine raster scan
                        scan_total_x_steps <= param1;
                        scan_total_y_steps <= param2;
                        scan_delta_y_steps <= param3;
                        if cmd_axis /= x"00" then
                            scan_delta_x_steps <= resize(unsigned(cmd_axis), 16);
                        else
                            scan_delta_x_steps <= to_unsigned(40, 16); -- Default 40 microsteps = 0.1mm @ 400 stp/mm
                        end if;
                        scan_curr_x_steps  <= (others => '0');
                        scan_curr_y_steps  <= (others => '0');
                        line_counter       <= (others => '0');
                        line_has_pulsed    <= '0';
                        pulse_step_cnt     <= (others => '0');
                        step_phase_x       <= '0';
                        step_phase_y       <= '0';
                        scan_done_hold_r   <= '0';
                        scan_status_r      <= x"00";

                        -- Configure initial directions from cmd_dir byte:
                        -- Bit 0: Initial X direction ('1' = +X, '0' = -X)
                        -- Bit 1: Y advance direction ('1' = +Y, '0' = -Y)
                        scan_x_dir         <= cmd_dir(0);
                        x_dir_r            <= cmd_dir(0);
                        scan_y_dir         <= cmd_dir(1);
                        y_dir_r            <= cmd_dir(1);

                        step_timer_x       <= 0;
                        step_timer_y       <= 0;
                        if motor_enable_r = '1' and motor_mode /= M_IDLE then
                            motor_mode <= M_SCAN_SWEEP_X;
                        else
                            motor_enable_r   <= '1';
                            wake_timer       <= WAKEUP_DELAY_CYCLES;
                            next_motion_mode <= M_SCAN_SWEEP_X;
                            motor_mode       <= M_WAKEUP;
                        end if;

                    when x"03" => -- CMD_SET_SPEED: Configure step pulse period
                        if param2 > 500 then
                            cfg_half_period <= to_integer(param2);
                        end if;

                    when x"04" => -- CMD_ZERO_POSITION
                        scan_curr_x_steps <= (others => '0');
                        scan_curr_y_steps <= (others => '0');

                    when others =>
                        null;
                end case;
            end if;

        end if;
    end process;

end Behavioral;
