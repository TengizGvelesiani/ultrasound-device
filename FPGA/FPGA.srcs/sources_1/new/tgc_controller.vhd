library IEEE;
use IEEE.STD_LOGIC_1164.ALL;
use IEEE.NUMERIC_STD.ALL;

-- ============================================================================
-- Module Name: tgc_controller
-- Description: Time-Gain Compensation (TGC) Sweep Generator & 16-bit SPI DAC Driver.
--              Generates analog attenuation/gain control voltage synchronized to
--              each ultrasound transmission strike to compensate for acoustic depth decay.
--              Tuned for 13-14 mm scanning depth with reduced amplification headroom:
--              - Starts at 128 (to suppress near-field pulser ring-down noise).
--              - Steps every 2.0 us (200 cycles @ 100 MHz, ~1.48 mm depth increment in water).
--              - Ramps moderately (+190 per step) to reach controlled peak gain (~1950) around
--                18-20 us (~13.3-14.8 mm depth) to prevent ADC clipping on strong container
--                reflections and preserve full dynamic range.
-- ============================================================================
entity tgc_controller is
    Port (
        clk100mhz     : in  STD_LOGIC;
        pulse_trigger : in  STD_LOGIC; -- Acoustic strike trigger from pulser_fsm

        -- SPI DAC Hardware Interface
        cs            : out STD_LOGIC;
        sck           : out STD_LOGIC;
        sci           : out STD_LOGIC;

        -- Subsystem Status
        spi_init_done : out STD_LOGIC
    );
end tgc_controller;

architecture Behavioral of tgc_controller is

    signal spi_state     : integer range 0 to 2 := 0;
    signal spi_bit_idx   : integer range 0 to 15 := 15;
    signal spi_clk_cnt   : integer := 0;
    signal cs_r          : STD_LOGIC := '1';
    signal sck_r         : STD_LOGIC := '0';
    signal sci_r         : STD_LOGIC := '0';
    signal spi_start     : STD_LOGIC := '0';
    signal spi_data_reg  : STD_LOGIC_VECTOR(15 downto 0) := x"3080"; 

    signal startup_timer : integer range 0 to 200 := 0;
    signal init_done_r   : STD_LOGIC := '0';

    signal tgc_timer     : integer := 0;
    signal tgc_step_cnt  : integer range 0 to 255 := 0;
    signal tgc_value     : integer range 0 to 4095 := 128;

begin

    cs            <= cs_r;
    sck           <= sck_r;
    sci           <= sci_r;
    spi_init_done <= init_done_r;

    -- 16-bit SPI DAC Serializer Engine
    process(clk100mhz)
    begin
        if rising_edge(clk100mhz) then
            if startup_timer < 150 then
                startup_timer <= startup_timer + 1;
            end if;

            if spi_state = 0 then
                cs_r  <= '1';
                sck_r <= '0';
                if init_done_r = '0' and spi_start = '0' and startup_timer >= 100 then
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
                        cs_r      <= '1';
                        spi_state <= 2;
                    else
                        spi_bit_idx <= spi_bit_idx - 1;
                    end if;
                else
                    spi_clk_cnt <= spi_clk_cnt + 1;
                end if;
            elsif spi_state = 2 then
                cs_r        <= '1';
                init_done_r <= '1';
                spi_state   <= 0;
            end if;
        end if;
    end process;

    -- Time-Gain Compensation Ramp Generator
    process(clk100mhz)
    begin
        if rising_edge(clk100mhz) then
            spi_start <= '0';

            if pulse_trigger = '1' then
                tgc_timer    <= 1;
                tgc_step_cnt <= 0;
                tgc_value    <= 128;
                spi_data_reg <= x"3080";
                spi_start    <= '1';
            elsif tgc_timer > 0 then
                if tgc_timer < 25000 then
                    tgc_timer <= tgc_timer + 1;
                else
                    tgc_timer <= 0;
                end if;

                -- Step every 200 cycles (2.0 us = ~1.48 mm depth increment in water).
                -- Tuned for ~13-14 mm scanning depth with lower amplification headroom:
                -- Ramps from 128 to ~1950 in ~18-20 us (+190 per step, max ceiling 1950)
                -- to prevent saturation/clipping on strong reflections (e.g. plastic container boundary).
                if tgc_step_cnt = 199 then
                    tgc_step_cnt <= 0;
                    if tgc_value < 1950 then
                        if tgc_value + 190 >= 1950 then
                            tgc_value    <= 1950;
                            spi_data_reg <= x"3" & STD_LOGIC_VECTOR(to_unsigned(1950, 12));
                        else
                            tgc_value    <= tgc_value + 190;
                            spi_data_reg <= x"3" & STD_LOGIC_VECTOR(to_unsigned(tgc_value + 190, 12));
                        end if;
                        spi_start <= '1';
                    end if;
                else
                    tgc_step_cnt <= tgc_step_cnt + 1;
                end if;
            end if;
        end if;
    end process;

end Behavioral;
