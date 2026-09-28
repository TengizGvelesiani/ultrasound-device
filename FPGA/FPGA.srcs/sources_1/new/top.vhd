library IEEE;
use IEEE.STD_LOGIC_1164.ALL;
use IEEE.NUMERIC_STD.ALL;

entity pulser_rtz is
    Port (
        CLK100MHZ : in  STD_LOGIC;
       
        -- MD1213
        oe        : out STD_LOGIC;
        ina       : out STD_LOGIC;
        inb       : out STD_LOGIC;
       
        -- LEDs
        rgb_led   : out STD_LOGIC_VECTOR(5 downto 0);
       
        -- DAC (MCP4821)
        cs        : out STD_LOGIC;
        sck       : out STD_LOGIC;
        sci       : out STD_LOGIC;
       
        -- ADC AD9226
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

    -- ===================== PULSER =====================
    constant PRF_CYCLES    : integer := 1_000_000; -- 10 ms @ 100 Hz
    constant STRIKE_CYCLES : integer := 10;        -- 100 ns
    constant DEAD_CYCLES   : integer := 15;        -- 150 ns
    constant DAMP_CYCLES   : integer := 10;        -- 100 ns
    constant POWERON_HOLD  : integer := 100_000;   -- 1 ms

    type state_type is (POWER_ON, IDLE, STRIKE, DEAD_TIME, DAMP);
    signal current_state : state_type := POWER_ON;
    signal last_state    : state_type := POWER_ON;

    signal cycle_counter : integer range 0 to PRF_CYCLES := 0;
    signal pulse_counter : integer range 0 to 49 := 0;
    signal heartbeat     : STD_LOGIC := '0';

    signal oe_r  : STD_LOGIC := '0';
    signal ina_r : STD_LOGIC := '0';
    signal inb_r : STD_LOGIC := '1';

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

    signal ram_idx      : integer range 0 to 1023 := 0;
    signal main_cnt     : integer := 0;

    signal adc_clk_div  : std_logic := '0';
    signal adc_clk_cnt  : integer range 0 to 15 := 0;

    signal uart_busy    : std_logic := '0';
    signal uart_start   : std_logic := '0';
    signal tx_char      : std_logic_vector(7 downto 0) := x"00";
    signal uart_shift_reg : std_logic_vector(10 downto 0) := (others => '1');
    signal baud_cnt     : integer range 0 to 1023 := 0;
    signal bit_idx      : integer range 0 to 10 := 0;

    signal current_sample : std_logic_vector(11 downto 0);
    signal char_idx       : integer range 0 to 4 := 0;

    -- ===================== TEST SIGNAL (48.8 kHz SQUARE WAVE) =====================
    constant SQ_HALF_PERIOD : integer := 1024;
    signal sq_cnt    : integer range 0 to SQ_HALF_PERIOD-1 := 0;
    signal test_pulse : std_logic := '0';

    -- ===================== DAC SPI SETUP =====================
    signal spi_state   : integer range 0 to 2 := 0;
    signal spi_bit_idx : integer range 0 to 15 := 15;
    signal spi_clk_cnt : integer := 0;
    signal cs_r        : std_logic := '1';
    signal sck_r       : std_logic := '0';
    signal sci_r       : std_logic := '0';
    
    constant spi_data  : std_logic_vector(15 downto 0) := x"33E8"; 

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

begin

    -- Outputs
    oe  <= oe_r;
    ina <= ina_r;
    inb <= inb_r;
    clk <= adc_clk_div;
    test_out <= test_pulse;

    cs  <= cs_r;
    sck <= sck_r;
    sci <= sci_r;

    rgb_led(1) <= heartbeat;
    rgb_led(4) <= heartbeat;
    rgb_led(0) <= '0';
    rgb_led(2) <= '0';
    rgb_led(3) <= '0';
    rgb_led(5) <= '0';

    -- ===================== DAC SPI PROCESS =====================
    process(CLK100MHZ)
    begin
        if rising_edge(CLK100MHZ) then
            if spi_state = 0 then
                if spi_clk_cnt = 1000 then
                    cs_r <= '0';
                    sci_r <= spi_data(15);
                    spi_clk_cnt <= 0;
                    spi_state <= 1;
                else
                    spi_clk_cnt <= spi_clk_cnt + 1;
                end if;
            elsif spi_state = 1 then
                if spi_clk_cnt = 49 then
                    sck_r <= '1';
                    spi_clk_cnt <= spi_clk_cnt + 1;
                elsif spi_clk_cnt = 99 then
                    sck_r <= '0';
                    spi_clk_cnt <= 0;
                    if spi_bit_idx = 0 then
                        cs_r <= '1';
                        spi_state <= 2;
                    else
                        spi_bit_idx <= spi_bit_idx - 1;
                        sci_r <= spi_data(spi_bit_idx - 1);
                    end if;
                else
                    spi_clk_cnt <= spi_clk_cnt + 1;
                end if;
            end if;
        end if;
    end process;


   -- ===================== PULSER PROCESS =====================
process(CLK100MHZ)
begin
    if rising_edge(CLK100MHZ) then
        -- Default = safe high-Z (both FETs OFF)
        oe_r  <= '1';
        ina_r <= '0';
        inb_r <= '0';
        last_state <= current_state;

        case current_state is
            when POWER_ON =>
                -- Official disable / pre-charge state
                oe_r  <= '0';
                ina_r <= '0';
                inb_r <= '1';
                if cycle_counter >= POWERON_HOLD-1 then
                    cycle_counter <= 0;
                    current_state <= IDLE;
                else
                    cycle_counter <= cycle_counter + 1;
                end if;

            when IDLE =>
                -- both FETs OFF (defaults already set)
                if cycle_counter >= PRF_CYCLES-1 then
                    cycle_counter <= 0;
                    current_state <= STRIKE;
                    if pulse_counter >= 49 then
                        pulse_counter <= 0;
                        heartbeat <= not heartbeat;
                    else
                        pulse_counter <= pulse_counter + 1;
                    end if;
                else
                    cycle_counter <= cycle_counter + 1;
                end if;

            when STRIKE =>
                -- Both FETs ON ? clamp to 0 V
                oe_r  <= '1';
                ina_r <= '1';
                inb_r <= '1';
                if cycle_counter >= STRIKE_CYCLES-1 then
                    cycle_counter <= 0;
                    current_state <= DEAD_TIME;
                else
                    cycle_counter <= cycle_counter + 1;
                end if;

            when DEAD_TIME =>
                -- both FETs OFF
                oe_r  <= '1';
                ina_r <= '0';
                inb_r <= '0';
                if cycle_counter >= DEAD_CYCLES-1 then
                    cycle_counter <= 0;
                    current_state <= DAMP;
                else
                    cycle_counter <= cycle_counter + 1;
                end if;

            when DAMP =>
                -- N-channel ON ? negative pulse
                oe_r  <= '1';
                ina_r <= '0';
                inb_r <= '1';          -- ? this is the important change
                if cycle_counter >= DAMP_CYCLES-1 then
                    cycle_counter <= 0;
                    current_state <= IDLE;
                else
                    cycle_counter <= cycle_counter + 1;
                end if;

            when others =>
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
                sq_cnt <= 0;
                test_pulse <= not test_pulse;
            else
                sq_cnt <= sq_cnt + 1;
            end if;

            -- UART transmitter
            if uart_start = '1' then
                uart_shift_reg <= "11" & tx_char & '0';
                bit_idx   <= 0;
                baud_cnt  <= 0;
                uart_busy <= '1';
                uart_tx   <= '0';
            elsif uart_busy = '1' then
                uart_tx <= uart_shift_reg(0);
                if baud_cnt = BAUD_RATE_TICKS-1 then
                    baud_cnt <= 0;
                    if bit_idx = 10 then
                        uart_busy <= '0';
                    else
                        uart_shift_reg <= '1' & uart_shift_reg(10 downto 1);
                        bit_idx <= bit_idx + 1;
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
                    if main_cnt = 100000 then
                        main_cnt     <= 0;
                        logger_state <= WAIT_PULSE;
                    else
                        main_cnt <= main_cnt + 1;
                    end if;

                when WAIT_PULSE =>
                    if current_state = STRIKE and last_state = IDLE then
                        ram_idx      <= 0;
                        logger_state <= CAPTURE_SAMPLES;
                    end if;

                    when CAPTURE_SAMPLES =>
                    if adc_clk_cnt = 4 and adc_clk_div = '0' then
                        
                        -- ======================================================
                        -- PHASE 3: THE DC BASELINE TEST
                        -- ======================================================
                        -- Bus is restored. Verify Gowin .cst physical pins are mapped 
                        -- MSB-to-MSB and LSB-to-LSB.
                        adc_ram(ram_idx) <= adc_data;
                        
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