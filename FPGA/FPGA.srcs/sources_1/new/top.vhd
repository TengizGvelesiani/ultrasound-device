library IEEE;
use IEEE.STD_LOGIC_1164.ALL;
use IEEE.NUMERIC_STD.ALL;

entity pulser_rtz is
    Port (
        CLK100MHZ : in  STD_LOGIC;

        -- High-Voltage 100V Supply Relay
        relay     : out STD_LOGIC;
        -- MD1213 MOSFET Driver
        oe        : out STD_LOGIC;
        ina       : out STD_LOGIC;
        inb       : out STD_LOGIC;

        -- LEDs
        rgb_led   : out STD_LOGIC_VECTOR(5 downto 0);

        -- DAC (MCP4821)
        cs        : out STD_LOGIC;
        sck       : out STD_LOGIC;
        sci       : out STD_LOGIC;

        -- ADC AD9226 (adc_data(n) is wired to BITn: BIT1 = MSB ... BIT12 = LSB)
        clk       : out STD_LOGIC;
        adc_data  : in  STD_LOGIC_VECTOR(12 downto 1);
        otr       : in  STD_LOGIC;

        -- UART Logger
        uart_tx   : out STD_LOGIC;

        -- TEST SIGNAL GENERATOR
        test_out  : out STD_LOGIC
    );
end pulser_rtz;

architecture Behavioral of pulser_rtz is

    -- ===================== PULSER & SAFETY TIMING =====================
    -- 100 MHz clock (10 ns per tick)
    constant POWERON_HOLD_CYCLES : integer := 100_000;   -- 1 ms initial driver hold
    constant RELAY_SETTLE_CYCLES : integer := 5_000_000; -- 50 ms for relay bounce + 100V cap charge
    constant ENABLE_HOLD_CYCLES  : integer := 10_000;    -- 100 us after OE='1' before first pulse

    -- Optimal 5 MHz Transducer Timing (Period = 200 ns)
    constant STRIKE_CYCLES       : integer := 10;        -- 100 ns (PMOS ON: Pull to 100V)
    constant DEAD_CYCLES         : integer := 4;         -- 40 ns  (Both OFF: maximum safety margin against shoot-through)
    constant DAMP_CYCLES         : integer := 10;        -- 100 ns (NMOS ON: active return to 0V)
    
    -- Active damping time after the pulse to mechanically kill transducer ringing
    constant ACTIVE_DAMP_CYCLES  : integer := 50;        -- 500 ns
    
    -- BURST_COUNT controls how many cycles are fired (1 for max resolution, 2+ for deeper penetration)
    constant BURST_COUNT         : integer := 3;

    -- FULL pulse-to-pulse period (10 ms @ 100 Hz PRF)
    constant PRF_CYCLES          : integer := 1_000_000;
    constant IDLE_CYCLES         : integer := PRF_CYCLES - (BURST_COUNT * (STRIKE_CYCLES + DEAD_CYCLES * 2 + DAMP_CYCLES) + ACTIVE_DAMP_CYCLES);

    type state_type is (
        POWER_ON,       -- OE='0', Relay='0', wait for DAC SPI to finish
        RELAY_SETTLE,   -- OE='0', Relay='1', wait 50 ms for 100V rail & 10nF cap pre-charge
        DRIVER_ENABLE,  -- OE='1', Relay='1', allow MD1213 threshold to settle
        IDLE,           -- Wait for next PRF trigger
        STRIKE,         -- P-Channel ON  (INA='1', INB='1')
        DEAD_TIME_1,    -- Both FETs OFF (INA='0', INB='1')
        DAMP,           -- N-Channel ON  (INA='0', INB='0')
        DEAD_TIME_2,    -- Both FETs OFF before next cycle
        ACTIVE_DAMP     -- Extended N-Channel ON to stop transducer ringing
    );
    signal current_state : state_type := POWER_ON;
    signal last_state    : state_type := POWER_ON;

    signal cycle_counter : integer range 0 to RELAY_SETTLE_CYCLES := 0;
    signal pulse_counter : integer range 0 to 49 := 0;
    signal burst_counter : integer range 0 to 15 := 0;
    signal heartbeat     : STD_LOGIC := '0';

    -- Safe initial values: OE='0' (disabled), INA='0' (OUTA=VH), INB='1' (OUTB=VL), Relay='0'
    signal relay_r : STD_LOGIC := '0';
    signal oe_r    : STD_LOGIC := '0';
    signal ina_r   : STD_LOGIC := '0';
    signal inb_r   : STD_LOGIC := '1';

    -- ===================== LOGGER =====================
    constant BAUD_RATE_TICKS : integer := 868; -- 100 MHz / 115200

    type logger_state_type is (
        INIT_WAIT,
        WAIT_PULSE,
        CAPTURE_SAMPLES,
        UART_PREP_SAMPLE,
        UART_SEND_CHAR,
        UART_WAIT_TX,
        UART_NEXT_CHAR
    );
    signal logger_state : logger_state_type := INIT_WAIT;

    type ram_type is array (0 to 1023) of std_logic_vector(11 downto 0);
    signal adc_ram : ram_type := (others => (others => '0'));
    signal ram_idx        : integer range 0 to 1023 := 0;
    signal main_cnt       : integer := 0;
    signal adc_clk_div    : std_logic := '0';
    signal adc_clk_cnt    : integer range 0 to 15 := 0;
    signal uart_busy      : std_logic := '0';
    signal uart_start     : std_logic := '0';
    signal tx_char        : std_logic_vector(7 downto 0) := x"00";
    signal uart_shift_reg : std_logic_vector(10 downto 0) := (others => '1');
    signal baud_cnt       : integer range 0 to 1023 := 0;
    signal bit_idx        : integer range 0 to 10 := 0;
    signal current_sample : std_logic_vector(11 downto 0);
    signal char_idx       : integer range 0 to 4 := 0;

    -- ADC word in true weight order (bit 11 = MSB = AD9226 BIT1, bit 0 = LSB = AD9226 BIT12)
    signal adc_word       : std_logic_vector(11 downto 0);

    -- ===================== TEST SIGNAL (48.8 kHz SQUARE WAVE) =====================
    constant SQ_HALF_PERIOD : integer := 1024;
    signal sq_cnt     : integer range 0 to SQ_HALF_PERIOD-1 := 0;
    signal test_pulse : std_logic := '0';

    -- ===================== DAC SPI SETUP & TGC =====================
    signal spi_state   : integer range 0 to 2 := 0;
    signal spi_bit_idx : integer range 0 to 15 := 15;
    signal spi_clk_cnt : integer := 0;
    signal cs_r        : std_logic := '1';
    signal sck_r       : std_logic := '0';
    signal sci_r       : std_logic := '0';
    
    signal spi_start    : std_logic := '0';
    signal spi_data_reg : std_logic_vector(15 downto 0) := x"3080"; -- Default low gain
    
    signal tgc_timer : integer := 0;
    signal tgc_step_cnt : integer range 0 to 255 := 0;
    signal tgc_value : integer range 0 to 4095 := 128;


    function to_hex_char(vec : std_logic_vector(3 downto 0)) return std_logic_vector is
        variable ext_vec : unsigned(7 downto 0);
    begin
        ext_vec := resize(unsigned(vec), 8);
        if ext_vec < 10 then
            return std_logic_vector(ext_vec + 48);
        else
            return std_logic_vector(ext_vec + 55);
        end if;
    end function;

    signal spi_init_done : std_logic := '0';

begin

    -- Outputs
    relay    <= relay_r;
    oe       <= oe_r;
    ina      <= ina_r;
    inb      <= inb_r;
    clk      <= adc_clk_div;
    test_out <= test_pulse;
    cs       <= cs_r;
    sck      <= sck_r;
    sci      <= sci_r;

    rgb_led(1) <= heartbeat;
    rgb_led(4) <= relay_r; -- Lights up when 100V relay is active
    rgb_led(0) <= '0';
    rgb_led(2) <= '0';
    rgb_led(3) <= '0';
    rgb_led(5) <= '0';

    -- AD9226 bit order: BIT1 is the MSB, BIT12 is the LSB.
    -- adc_data(n) is wired to BITn, so adc_data(1) must become the MSB of the word.
    adc_word <= adc_data(1)  & adc_data(2)  & adc_data(3)  & adc_data(4)  &
                adc_data(5)  & adc_data(6)  & adc_data(7)  & adc_data(8)  &
                adc_data(9)  & adc_data(10) & adc_data(11) & adc_data(12);

    -- ===================== DAC SPI PROCESS (10 MHz) =====================
    process(CLK100MHZ)
    begin
        if rising_edge(CLK100MHZ) then
            if spi_state = 0 then
                cs_r  <= '1';
                sck_r <= '0';
                
                -- Auto-trigger the first configuration for boot
                if spi_init_done = '0' and spi_start = '0' and cycle_counter > 100 then
                    spi_clk_cnt <= 0;
                    spi_bit_idx <= 15;
                    spi_state   <= 1;
                elsif spi_start = '1' then
                    spi_clk_cnt <= 0;
                    spi_bit_idx <= 15;
                    spi_state   <= 1;
                end if;
                
            elsif spi_state = 1 then
                if spi_clk_cnt = 0 then
                    cs_r  <= '0';
                    sci_r <= spi_data_reg(spi_bit_idx);
                    spi_clk_cnt <= spi_clk_cnt + 1;
                elsif spi_clk_cnt = 4 then
                    sck_r <= '1';
                    spi_clk_cnt <= spi_clk_cnt + 1;
                elsif spi_clk_cnt = 9 then
                    sck_r <= '0';
                    spi_clk_cnt <= 0;
                    if spi_bit_idx = 0 then
                        cs_r <= '1';
                        spi_state <= 2;
                    else
                        spi_bit_idx <= spi_bit_idx - 1;
                    end if;
                else
                    spi_clk_cnt <= spi_clk_cnt + 1;
                end if;
            elsif spi_state = 2 then
                cs_r <= '1';
                spi_init_done <= '1';
                spi_state <= 0; -- return to IDLE
            end if;
        end if;
    end process;

    -- ===================== TGC SWEEP PROCESS (Smooth Ramp) =====================
    process(CLK100MHZ)
    begin
        if rising_edge(CLK100MHZ) then
            spi_start <= '0'; -- default

            if current_state = STRIKE and last_state = IDLE then
                tgc_timer <= 1;
                tgc_step_cnt <= 0;
                tgc_value <= 128; -- start gain (~0.06V)
                spi_data_reg <= x"3080"; -- 0x3080 = Gain 128
                spi_start <= '1';
            elsif tgc_timer > 0 then
                if tgc_timer < 25000 then
                    tgc_timer <= tgc_timer + 1;
                else
                    tgc_timer <= 0; -- stop after 250 us
                end if;

                -- Smoothly ramp the DAC up to 2000 over the 200us window
                -- Update every 200 clock ticks (2.0 us)
                if tgc_step_cnt = 199 then
                    tgc_step_cnt <= 0;
                    if tgc_value < 2000 then
                        tgc_value <= tgc_value + 19; -- step up (~10 mV)
                        spi_data_reg <= x"3" & std_logic_vector(to_unsigned(tgc_value, 12));
                        spi_start <= '1';
                    end if;
                else
                    tgc_step_cnt <= tgc_step_cnt + 1;
                end if;
            end if;
        end if;
    end process;

    -- ===================== PULSER & RELAY PROCESS =====================
    process(CLK100MHZ)
    begin
        if rising_edge(CLK100MHZ) then

            -- Default safe state: Both FETs OFF (OUTA = VH, OUTB = VL)
            oe_r       <= '1';
            ina_r      <= '0';
            inb_r      <= '1';
            last_state <= current_state;

            case current_state is

                when POWER_ON =>
                    -- Keep Relay OFF and MD1213 in Hardware Disable/Pre-charge mode
                    relay_r <= '0';
                    oe_r    <= '0';
                    ina_r   <= '0';
                    inb_r   <= '1';

                    -- Only advance after 1 ms AND after DAC SPI initialization is complete
                    if cycle_counter >= POWERON_HOLD_CYCLES - 1 and spi_init_done = '1' then
                        cycle_counter <= 0;
                        current_state <= RELAY_SETTLE;
                    elsif cycle_counter < POWERON_HOLD_CYCLES - 1 then
                        cycle_counter <= cycle_counter + 1;
                    end if;

                when RELAY_SETTLE =>
                    -- Turn ON 100V Relay while MD1213 is still hardware-disabled (OE='0')
                    -- This safely pre-charges the 10nF AC coupling caps as the 100V rail rises
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
                    -- Enable MD1213 (OE='1') with both FETs OFF (INA='0', INB='1')
                    -- Allows MD1213 input threshold (VOE/2) to stabilize before pulsing
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
                    -- Both FETs OFF (OE='1', INA='0' -> OUTA=VH, INB='1' -> OUTB=VL)
                    relay_r <= '1';
                    oe_r    <= '1';
                    ina_r   <= '0';
                    inb_r   <= '1';

                    if cycle_counter >= IDLE_CYCLES - 1 then
                        cycle_counter <= 0;
                        burst_counter <= 0;
                        current_state <= STRIKE;
                        if pulse_counter >= 49 then
                            pulse_counter <= 0;
                            heartbeat     <= not heartbeat;
                        else
                            pulse_counter <= pulse_counter + 1;
                        end if;
                    else
                        cycle_counter <= cycle_counter + 1;
                    end if;

                when STRIKE =>
                    -- P-Channel ON (INA='1' -> OUTA=VL), N-Channel OFF (INB='1' -> OUTB=VL)
                    -- Drives positive HV pulse into 5 MHz transducer
                    relay_r <= '1';
                    oe_r    <= '1';
                    ina_r   <= '1';
                    inb_r   <= '1';

                    if cycle_counter >= STRIKE_CYCLES - 1 then
                        cycle_counter <= 0;
                        current_state <= DEAD_TIME_1;
                    else
                        cycle_counter <= cycle_counter + 1;
                    end if;

                when DEAD_TIME_1 =>
                    -- Both FETs OFF to prevent shoot-through
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
                    -- P-Channel OFF (INA='0' -> OUTA=VH), N-Channel ON (INB='0' -> OUTB=VH)
                    -- Active return-to-zero: output pulled to 0 V
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
                    -- Both FETs OFF to prevent shoot-through before next STRIKE
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
                    -- Final extended clamping to GND to stop all transducer mechanical ringing
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

    -- ===================== LOGGER & TEST SIGNAL PROCESS =====================
    process(CLK100MHZ)
    begin
        if rising_edge(CLK100MHZ) then

            -- ADC clock generation (5 MHz)
            if adc_clk_cnt = 9 then
                adc_clk_div <= not adc_clk_div;
                adc_clk_cnt <= 0;
            else
                adc_clk_cnt <= adc_clk_cnt + 1;
            end if;

            -- Test Signal Generation (48.8 kHz SQUARE WAVE)
            if sq_cnt = SQ_HALF_PERIOD-1 then
                sq_cnt     <= 0;
                test_pulse <= not test_pulse;
            else
                sq_cnt <= sq_cnt + 1;
            end if;

            -- UART transmitter
            if uart_start = '1' then
                uart_shift_reg <= "11" & tx_char & '0';
                bit_idx        <= 0;
                baud_cnt       <= 0;
                uart_busy      <= '1';
                uart_tx        <= '0';
            elsif uart_busy = '1' then
                uart_tx <= uart_shift_reg(0);
                if baud_cnt = BAUD_RATE_TICKS-1 then
                    baud_cnt <= 0;
                    if bit_idx = 10 then
                        uart_busy <= '0';
                    else
                        uart_shift_reg <= '1' & uart_shift_reg(10 downto 1);
                        bit_idx        <= bit_idx + 1;
                    end if;
                else
                    baud_cnt <= baud_cnt + 1;
                end if;
            else
                uart_tx <= '1';
            end if;

            uart_start <= '0';

            case logger_state is

                when INIT_WAIT =>
                    -- Wait until the pulser has finished relay settling and entered IDLE
                    if current_state = IDLE then
                        main_cnt     <= 0;
                        logger_state <= WAIT_PULSE;
                    end if;

                when WAIT_PULSE =>
                    if current_state = STRIKE and last_state = IDLE then
                        ram_idx      <= 0;
                        logger_state <= CAPTURE_SAMPLES;
                    end if;

                when CAPTURE_SAMPLES =>
                    if adc_clk_cnt = 4 and adc_clk_div = '0' then
                        adc_ram(ram_idx) <= adc_word;

                        if ram_idx = 1023 then
                            ram_idx      <= 0;
                            logger_state <= UART_PREP_SAMPLE;
                        else
                            ram_idx <= ram_idx + 1;
                        end if;
                    end if;

                when UART_PREP_SAMPLE =>
                    current_sample <= adc_ram(ram_idx);
                    char_idx       <= 0;
                    logger_state   <= UART_SEND_CHAR;

                when UART_SEND_CHAR =>
                    if uart_busy = '0' then
                        uart_start <= '1';
                        if    char_idx = 0 then tx_char <= to_hex_char(current_sample(11 downto 8));
                        elsif char_idx = 1 then tx_char <= to_hex_char(current_sample(7 downto 4));
                        elsif char_idx = 2 then tx_char <= to_hex_char(current_sample(3 downto 0));
                        elsif char_idx = 3 then tx_char <= x"0D";
                        else                    tx_char <= x"0A";
                        end if;
                        logger_state <= UART_WAIT_TX;
                    end if;

                when UART_WAIT_TX =>
                    if uart_busy = '1' then
                        logger_state <= UART_NEXT_CHAR;
                    end if;

                when UART_NEXT_CHAR =>
                    if uart_busy = '0' then
                        if char_idx = 4 then
                            if ram_idx = 1023 then
                                logger_state <= WAIT_PULSE;
                            else
                                ram_idx      <= ram_idx + 1;
                                logger_state <= UART_PREP_SAMPLE;
                            end if;
                        else
                            char_idx     <= char_idx + 1;
                            logger_state <= UART_SEND_CHAR;
                        end if;
                    end if;

            end case;
        end if;
    end process;

end Behavioral;