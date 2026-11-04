library IEEE;
use IEEE.STD_LOGIC_1164.ALL;
use IEEE.NUMERIC_STD.ALL;

-- ============================================================================
-- Module Name: pulser_fsm
-- Description: High-Voltage Ultrasound Pulser Sequence & Protection Controller.
--              Manages relay charging, gate driver enable, 3-cycle 5 MHz burst
--              generation (STRIKE/DAMP), active ringing suppression, and PRF timing.
--              Supports both Free-Running 100 Hz PRF (Benchtop Live Mode) and
--              Position-Triggered firing with Hardware Gating (Autonomous Chamber Scan).
-- ============================================================================
entity pulser_fsm is
    Port (
        clk100mhz         : in  STD_LOGIC;
        spi_init_done     : in  STD_LOGIC;

        -- Position-Trigger Mode & Hardware Gating from Stepper Controller
        scan_mode_active  : in  STD_LOGIC; -- HIGH during autonomous chamber scan
        pos_pulse_trigger : in  STD_LOGIC; -- 1-cycle strobe from stepper_controller every N microsteps

        -- Gate Driver & Protection Controls
        relay             : out STD_LOGIC;
        oe                : out STD_LOGIC;
        ina               : out STD_LOGIC;
        inb               : out STD_LOGIC;

        -- Status & Subsystem Synchronization
        heartbeat         : out STD_LOGIC;
        pulse_trigger     : out STD_LOGIC -- 1-cycle strobe at start of acoustic pulse
    );
end pulser_fsm;

architecture Behavioral of pulser_fsm is

    -- Hardware Timing Constants (at 100 MHz clock = 10 ns per cycle)
    constant POWERON_HOLD_CYCLES : integer := 100_000;   -- 1 ms power-on delay
    constant RELAY_SETTLE_CYCLES : integer := 5_000_000; -- 50 ms relay debounce & cap charging
    constant ENABLE_HOLD_CYCLES  : integer := 10_000;    -- 100 us driver pre-enable

    constant STRIKE_CYCLES       : integer := 6;         -- 60 ns PMOS strike (half-cycle of ~5 MHz)
    constant DEAD_CYCLES         : integer := 4;         -- 40 ns shoot-through protection dead time
    constant DAMP_CYCLES         : integer := 6;         -- 60 ns NMOS damp
    constant ACTIVE_DAMP_CYCLES  : integer := 50;        -- 500 ns post-burst active ringing damp
    constant BURST_COUNT         : integer := 3;         -- 3 excitation cycles per acoustic pulse

    constant PRF_CYCLES          : integer := 1_000_000; -- 100 Hz Pulse Repetition Frequency
    constant IDLE_CYCLES         : integer := PRF_CYCLES - (BURST_COUNT * (STRIKE_CYCLES + DEAD_CYCLES * 2 + DAMP_CYCLES) + ACTIVE_DAMP_CYCLES);

    type state_type is (
        POWER_ON,
        RELAY_SETTLE,
        DRIVER_ENABLE,
        IDLE,
        STRIKE,
        DEAD_TIME_1,
        DAMP,
        DEAD_TIME_2,
        ACTIVE_DAMP
    );

    signal current_state : state_type := POWER_ON;
    signal last_state    : state_type := POWER_ON;

    signal cycle_counter : integer range 0 to RELAY_SETTLE_CYCLES := 0;
    signal pulse_counter : integer range 0 to 49 := 0;
    signal burst_counter : integer range 0 to 15 := 0;

    signal heartbeat_r   : STD_LOGIC := '0';
    signal relay_r       : STD_LOGIC := '0';
    signal oe_r          : STD_LOGIC := '0';
    signal ina_r         : STD_LOGIC := '0';
    signal inb_r         : STD_LOGIC := '0';
    signal trigger_r     : STD_LOGIC := '0';

begin

    relay         <= relay_r;
    oe            <= oe_r;
    ina           <= ina_r;
    inb           <= inb_r;
    heartbeat     <= heartbeat_r;
    pulse_trigger <= trigger_r;

    process(clk100mhz)
    begin
        if rising_edge(clk100mhz) then
            -- Default defaults
            oe_r      <= '1';
            ina_r     <= '0';
            inb_r     <= '1';
            trigger_r <= '0';
            last_state <= current_state;

            case current_state is
                when POWER_ON =>
                    relay_r <= '0';
                    oe_r    <= '0';
                    ina_r   <= '0';
                    inb_r   <= '1';
                    if cycle_counter >= POWERON_HOLD_CYCLES - 1 and spi_init_done = '1' then
                        cycle_counter <= 0;
                        current_state <= RELAY_SETTLE;
                    elsif cycle_counter < POWERON_HOLD_CYCLES - 1 then
                        cycle_counter <= cycle_counter + 1;
                    end if;

                when RELAY_SETTLE =>
                    relay_r <= '1';
                    oe_r    <= '0';
                    ina_r   <= '0';
                    inb_r   <= '1';
                    if cycle_counter >= RELAY_SETTLE_CYCLES - 1 then
                        cycle_counter <= 0;
                        current_state <= DRIVER_ENABLE;
                    else
                        cycle_counter <= cycle_counter + 1;
                    end if;

                when DRIVER_ENABLE =>
                    relay_r <= '1';
                    oe_r    <= '1';
                    ina_r   <= '0';
                    inb_r   <= '1';
                    if cycle_counter >= ENABLE_HOLD_CYCLES - 1 then
                        cycle_counter <= 0;
                        current_state <= IDLE;
                    else
                        cycle_counter <= cycle_counter + 1;
                    end if;

                when IDLE =>
                    relay_r <= '1';
                    oe_r    <= '1';
                    ina_r   <= '0';
                    inb_r   <= '1';
                    if scan_mode_active = '1' then
                        -- Position-Triggered Mode during Chamber Scan:
                        -- Blind 100 Hz PRF timer is inhibited to eliminate acceleration skew
                        -- and serpentine herringbone comb phase shifts.
                        cycle_counter <= 0;
                        if pos_pulse_trigger = '1' then
                            burst_counter <= 0;
                            current_state <= STRIKE;
                            if pulse_counter >= 49 then
                                pulse_counter <= 0;
                                heartbeat_r   <= not heartbeat_r;
                            else
                                pulse_counter <= pulse_counter + 1;
                            end if;
                        end if;
                    else
                        -- Benchtop Live Mode (Stationary / Manual Jog):
                        -- Free-running 100 Hz PRF timer for real-time acoustic feedback.
                        if cycle_counter >= IDLE_CYCLES - 1 then
                            cycle_counter <= 0;
                            burst_counter <= 0;
                            current_state <= STRIKE;
                            if pulse_counter >= 49 then
                                pulse_counter <= 0;
                                heartbeat_r   <= not heartbeat_r;
                            else
                                pulse_counter <= pulse_counter + 1;
                            end if;
                        else
                            cycle_counter <= cycle_counter + 1;
                        end if;
                    end if;

                when STRIKE =>
                    relay_r <= '1';
                    oe_r    <= '1';
                    ina_r   <= '1';
                    inb_r   <= '1';
                    -- Pulse trigger asserted on the very first cycle of STRIKE from IDLE
                    if last_state = IDLE then
                        trigger_r <= '1';
                    end if;
                    if cycle_counter >= STRIKE_CYCLES - 1 then
                        cycle_counter <= 0;
                        current_state <= DEAD_TIME_1;
                    else
                        cycle_counter <= cycle_counter + 1;
                    end if;

                when DEAD_TIME_1 =>
                    relay_r <= '1';
                    oe_r    <= '1';
                    ina_r   <= '0';
                    inb_r   <= '1';
                    if cycle_counter >= DEAD_CYCLES - 1 then
                        cycle_counter <= 0;
                        current_state <= DAMP;
                    else
                        cycle_counter <= cycle_counter + 1;
                    end if;

                when DAMP =>
                    relay_r <= '1';
                    oe_r    <= '1';
                    ina_r   <= '0';
                    inb_r   <= '0';
                    if cycle_counter >= DAMP_CYCLES - 1 then
                        cycle_counter <= 0;
                        if burst_counter >= BURST_COUNT - 1 then
                            current_state <= ACTIVE_DAMP;
                        else
                            burst_counter <= burst_counter + 1;
                            current_state <= DEAD_TIME_2;
                        end if;
                    else
                        cycle_counter <= cycle_counter + 1;
                    end if;

                when DEAD_TIME_2 =>
                    relay_r <= '1';
                    oe_r    <= '1';
                    ina_r   <= '0';
                    inb_r   <= '1';
                    if cycle_counter >= DEAD_CYCLES - 1 then
                        cycle_counter <= 0;
                        current_state <= STRIKE;
                    else
                        cycle_counter <= cycle_counter + 1;
                    end if;

                when ACTIVE_DAMP =>
                    relay_r <= '1';
                    oe_r    <= '1';
                    ina_r   <= '0';
                    inb_r   <= '0';
                    if cycle_counter >= ACTIVE_DAMP_CYCLES - 1 then
                        cycle_counter <= 0;
                        current_state <= IDLE;
                    else
                        cycle_counter <= cycle_counter + 1;
                    end if;

                when others =>
                    relay_r       <= '0';
                    oe_r          <= '0';
                    ina_r         <= '0';
                    inb_r         <= '1';
                    current_state <= POWER_ON;
            end case;
        end if;
    end process;

end Behavioral;
